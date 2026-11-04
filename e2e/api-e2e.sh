#!/usr/bin/env bash
# API-level verification: lifecycle, idempotency, authorization (IDOR), refunds, shipment->order sync, reviews.
# Usage: api-e2e.sh <db_name> [port]
DB="$1"; B="localhost:${2:-8080}"; J='Content-Type: application/json'
PASS=0; FAIL=0
q(){ docker exec dsports-postgres psql -U dsports -d "$DB" -tAc "$1"; }
check(){ # check <label> <expected> <actual>
  if [ "$2" = "$3" ]; then PASS=$((PASS+1)); printf "  PASS %-62s %s\n" "$1" "$3";
  else FAIL=$((FAIL+1)); printf "  FAIL %-62s expected=%s actual=%s\n" "$1" "$2" "$3"; fi; }
call(){ # call METHOD path token [body] [extra-header] -> sets STATUS and BODY
  local m=$1 p=$2 t=$3 d=$4 h=$5; local args=(-s -o /tmp/api_b -w '%{http_code}' -X "$m" "$B$p" -H "$J")
  [ -n "$t" ] && args+=(-H "Authorization: Bearer $t"); [ -n "$h" ] && args+=(-H "$h"); [ -n "$d" ] && args+=(-d "$d")
  STATUS=$(curl "${args[@]}"); BODY=$(cat /tmp/api_b); }
field(){ echo "$BODY" | grep -oE "\"$1\":(\"[^\"]*\"|[0-9.]+|null|true|false)" | head -1 | cut -d: -f2- | tr -d '"'; }
login(){ curl -s -XPOST $B/api/auth/register -H "$J" -d "{\"email\":\"$1\",\"password\":\"Passw0rd!x\",\"firstName\":\"T\",\"lastName\":\"U\"}" >/dev/null
  curl -s -XPOST $B/api/auth/login -H "$J" -d "{\"email\":\"$1\",\"password\":\"Passw0rd!x\"}" | sed -E 's/.*"accessToken":"([^"]+)".*/\1/'; }
buy(){ # buy <token> <productId> <qty> -> ORDER, TOTAL
  local A=$1; call POST /api/v1/cart/items "$A" "{\"productId\":\"$2\",\"quantity\":$3}"
  call POST /api/customers/me/addresses "$A" '{"line1":"1 MG Road","line2":"Flat 4","city":"Bengaluru","state":"KA","country":"IN","postalCode":"560001","type":"SHIPPING"}'; local AID=$(field addressId)
  call POST /api/v1/checkout "$A"; local CID=$(field id)
  call POST /api/v1/checkout/$CID/address "$A" "{\"addressId\":\"$AID\"}"
  call POST /api/v1/checkout/$CID/delivery "$A" '{"deliveryMethodCode":"STANDARD"}'
  call POST /api/v1/checkout/$CID/validate "$A"
  call POST /api/v1/orders "$A" "{\"checkoutId\":\"$CID\"}"; ORDER=$(field id); TOTAL=$(field grandTotal); CHECKOUT=$CID; }
pay(){ # pay <token> <order> <total> -> PAYMENT
  call POST /api/v1/payments "$1" "{\"orderId\":\"$2\",\"amount\":$3,\"currency\":\"INR\",\"paymentMethod\":\"UPI\",\"paymentProvider\":\"MOCK\"}"; PAYMENT=$(field id)
  call POST /api/v1/payments/$PAYMENT/capture "$1"; }

P1=00000000-0000-0000-0000-000000001001; P2=00000000-0000-0000-0000-000000001002; P3=00000000-0000-0000-0000-000000001004
TA=$(login alice-$RANDOM@example.com); TB=$(login bob-$RANDOM@example.com)
ADMIN_EMAIL=admin-$RANDOM@example.com; login $ADMIN_EMAIL >/dev/null
q "INSERT INTO customer_roles (customer_id, role) SELECT id, 'ADMIN' FROM customers WHERE email='$ADMIN_EMAIL'" >/dev/null
TADM=$(curl -s -XPOST $B/api/auth/login -H "$J" -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"Passw0rd!x\"}" | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')

echo "== Order + payment lifecycle"
STOCK0=$(q "select available_quantity-reserved_quantity from inventory where product_id='$P1'")
buy "$TA" $P1 2
check "order placed status" PENDING_PAYMENT "$(q "select status from orders where id='$ORDER'")"
check "stock reserved at placement" "$((STOCK0-2))" "$(q "select available_quantity-reserved_quantity from inventory where product_id='$P1'")"
check "reservation rows" "RESERVED:2" "$(q "select status||':'||quantity from inventory_reservations where order_id='$ORDER'")"
check "checkout completed" COMPLETED "$(q "select status from checkouts where id='$CHECKOUT'")"
check "order snapshot recipient name" "T U" "$(q "select shipping_address_snapshot from orders where id='$ORDER'" | grep -oE '"fullName":"[^"]*"' | cut -d'"' -f4)"
call POST /api/v1/orders "$TA" "{\"checkoutId\":\"$CHECKOUT\"}"; check "duplicate order" 409 "$STATUS"
call POST /api/v1/payments "$TA" "{\"orderId\":\"$ORDER\",\"amount\":1.00,\"currency\":\"INR\",\"paymentMethod\":\"UPI\",\"paymentProvider\":\"MOCK\"}"; check "wrong amount" "400 AMOUNT_MISMATCH" "$STATUS $(field code)"
call POST /api/v1/payments "$TB" "{\"orderId\":\"$ORDER\",\"amount\":$TOTAL,\"currency\":\"INR\",\"paymentMethod\":\"UPI\",\"paymentProvider\":\"MOCK\"}"; check "B pays A's order (IDOR)" 403 "$STATUS"
call POST /api/v1/payments "$TA" "{\"orderId\":\"$ORDER\",\"amount\":$TOTAL,\"currency\":\"INR\",\"paymentMethod\":\"UPI\",\"paymentProvider\":\"MOCK\"}"; PAYMENT=$(field id); check "authorize" AUTHORIZED "$(field status)"
call POST /api/v1/payments "$TA" "{\"orderId\":\"$ORDER\",\"amount\":$TOTAL,\"currency\":\"INR\",\"paymentMethod\":\"UPI\",\"paymentProvider\":\"MOCK\"}"; check "repeat create returns same open payment" "$PAYMENT" "$(field id)"
call POST /api/v1/payments/$PAYMENT/capture "$TB"; check "B captures A's payment (IDOR)" 403 "$STATUS"
call POST /api/v1/payments/$PAYMENT/capture "$TA"; check "capture" "200 SUCCESS" "$STATUS $(field status)"
call POST /api/v1/payments/$PAYMENT/capture "$TA"; check "duplicate capture is idempotent" "200 SUCCESS" "$STATUS $(field status)"
check "one payment row for order" 1 "$(q "select count(*) from payments where order_id='$ORDER'")"
check "order confirmed" CONFIRMED "$(q "select status from orders where id='$ORDER'")"
check "cart completed" COMPLETED "$(q "select c.status from carts c join checkouts k on k.cart_id=c.id where k.id='$CHECKOUT'")"
check "stock fulfilled (on hand -2, nothing reserved for order)" "FULFILLED:2" "$(q "select status||':'||quantity from inventory_reservations where order_id='$ORDER'")"
call POST /api/v1/payments "$TA" "{\"orderId\":\"$ORDER\",\"amount\":$TOTAL,\"currency\":\"INR\",\"paymentMethod\":\"UPI\",\"paymentProvider\":\"MOCK\"}"; check "pay already-paid order" "409 ORDER_ALREADY_PAID" "$STATUS $(field code)"
call PUT /api/v1/orders/$ORDER/cancel "$TA"; check "cancel paid order" 409 "$STATUS"
sleep 4
check "outbox delivered (no pending/failed)" "0" "$(q "select count(*) from outbox_events where status in ('PENDING','PROCESSING','FAILED')")"
check "shipment created from order snapshot" "1 MG Road, Flat 4" "$(q "select street from shipments where order_id='$ORDER'")"
check "notifications for order" "ORDER_CONFIRMED,ORDER_PLACED,PAYMENT_SUCCESS" "$(q "select string_agg(type, ',' order by type) from notifications where user_id=(select user_id from orders where id='$ORDER')")"

echo "== Authorization / IDOR"
call GET /api/v1/orders/$ORDER "$TB"; check "B reads A's order" 403 "$STATUS"
call GET /api/v1/payments/$PAYMENT "$TB"; check "B reads A's payment" 403 "$STATUS"
call GET /api/v1/payments/$PAYMENT/refunds "$TB"; check "B lists A's refunds" 403 "$STATUS"
call PUT /api/v1/orders/$ORDER/cancel "$TB"; check "B cancels A's order" 403 "$STATUS"
SHIP=$(q "select id from shipments where order_id='$ORDER'"); TRK=$(q "select tracking_number from shipments where order_id='$ORDER'")
call GET /api/v1/shipments/$SHIP "$TB"; check "B reads A's shipment" 403 "$STATUS"
call GET /api/v1/shipments/track/$TRK "$TB"; check "B tracks A's shipment (address leak)" 404 "$STATUS"
call GET /api/v1/shipments/track/$TRK "$TA"; check "A tracks own shipment" 200 "$STATUS"
call POST /api/v1/shipments "$TA" "{\"orderId\":\"$ORDER\"}"; check "customer creates shipment" 403 "$STATUS"
call GET /api/v1/admin/orders/$ORDER "$TA"; check "customer -> admin order API" 403 "$STATUS"
call GET /api/v1/admin/refunds "$TA"; check "customer -> admin refund API" 403 "$STATUS"
call GET /api/v1/admin/outbox "$TA"; check "customer -> admin outbox API" 403 "$STATUS"
call GET /api/v1/cart "" ; check "no token" 401 "$STATUS"
call GET /api/v1/cart "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.forged"; check "forged token" 401 "$STATUS"
call GET /api/inventory/$P1 ""; check "public stock has no internal fields" "" "$(echo "$BODY" | grep -oE 'warehouseId|reservedQuantity|reorderLevel' | head -1)"
call POST /api/v1/payments/$PAYMENT/refund "$TA"; check "old self-refund endpoint gone" "404" "$STATUS"

echo "== Refunds"
call POST /api/v1/payments/$PAYMENT/refund-requests "$TA" '{"reason":"no key"}'; check "refund request without Idempotency-Key" 400 "$STATUS"
call POST /api/v1/payments/$PAYMENT/refund-requests "$TB" '{"reason":"x"}' "Idempotency-Key: b-1"; check "B requests refund of A's payment" 403 "$STATUS"
call POST /api/v1/payments/$PAYMENT/refund-requests "$TA" "{\"amount\":999999}" "Idempotency-Key: a-0"; check "refund above captured amount" 400 "$STATUS"
call POST /api/v1/payments/$PAYMENT/refund-requests "$TA" '{"amount":50.00,"reason":"damaged"}' "Idempotency-Key: a-1"; R1=$(field id); check "customer refund request" "201 REQUESTED" "$STATUS $(field status)"
call POST /api/v1/payments/$PAYMENT/refund-requests "$TA" '{"amount":50.00,"reason":"damaged"}' "Idempotency-Key: a-1"; check "same key returns same refund" "$R1" "$(field id)"
check "customer request pays nothing out" "0.00 SUCCESS" "$(q "select refunded_amount||' '||status from payments where id='$PAYMENT'")"
call POST /api/v1/admin/refunds/$R1/approve "$TA"; check "customer approves own refund" 403 "$STATUS"
call POST /api/v1/admin/refunds/$R1/approve "$TADM"; check "admin approves" "200 COMPLETED" "$STATUS $(field status)"
call POST /api/v1/admin/refunds/$R1/approve "$TADM"; check "approve twice" 409 "$STATUS"
check "partial refund recorded" "50.00 PARTIALLY_REFUNDED" "$(q "select refunded_amount||' '||status from payments where id='$PAYMENT'")"
call POST /api/v1/admin/payments/$PAYMENT/refunds "$TADM" '{"reason":"goodwill"}' "Idempotency-Key: adm-1"; check "admin refunds remainder" "201 COMPLETED" "$STATUS $(field status)"
check "fully refunded" "$TOTAL REFUNDED" "$(q "select refunded_amount||' '||status from payments where id='$PAYMENT'")"
check "order refunded" REFUNDED "$(q "select status from orders where id='$ORDER'")"
call POST /api/v1/payments/$PAYMENT/refund-requests "$TA" '{}' "Idempotency-Key: a-2"; check "nothing left to refund" 409 "$STATUS"

echo "== Unpaid order cancellation releases stock"
S2=$(q "select available_quantity-reserved_quantity from inventory where product_id='$P2'")
buy "$TA" $P2 3
check "reserved 3" "$((S2-3))" "$(q "select available_quantity-reserved_quantity from inventory where product_id='$P2'")"
call POST /api/v1/payments "$TA" "{\"orderId\":\"$ORDER\",\"amount\":$TOTAL,\"currency\":\"INR\",\"paymentMethod\":\"UPI\",\"paymentProvider\":\"MOCK\"}"; P_OPEN=$(field id)
call PUT /api/v1/orders/$ORDER/cancel "$TA"; check "cancel unpaid order" "200 CANCELLED" "$STATUS $(field status)"
check "stock released" "$S2" "$(q "select available_quantity-reserved_quantity from inventory where product_id='$P2'")"
check "open payment voided" CANCELLED "$(q "select status from payments where id='$P_OPEN'")"
call POST /api/v1/payments/$P_OPEN/capture "$TA"; check "capture after cancellation" 409 "$STATUS"

echo "== Shipment progression -> order DELIVERED -> review"
buy "$TA" $P3 1; pay "$TA" $ORDER $TOTAL; sleep 4
SHIP=$(q "select id from shipments where order_id='$ORDER'")
for s in PICKED_UP IN_TRANSIT OUT_FOR_DELIVERY; do call PUT /api/v1/shipments/admin/$SHIP/status "$TADM" "{\"newStatus\":\"$s\"}"; done
check "order shipped with shipment" SHIPPED "$(q "select status from orders where id='$ORDER'")"
call PUT /api/v1/shipments/admin/$SHIP/status "$TADM" '{"newStatus":"DELIVERED"}'
check "order delivered with shipment" DELIVERED "$(q "select status from orders where id='$ORDER'")"
call GET "/api/v1/reviews/eligibility?productId=$P3" "$TA"; check "eligible to review" "true $ORDER" "$(field eligible) $(field orderId)"
call GET "/api/v1/reviews/eligibility?productId=$P3" "$TB"; check "B not eligible" false "$(field eligible)"
call POST /api/v1/reviews "$TB" "{\"productId\":\"$P3\",\"orderId\":\"$ORDER\",\"rating\":5,\"title\":\"x\"}"; check "B reviews with A's order" "404 ORDER_NOT_FOUND" "$STATUS $(field code)"
call POST /api/v1/reviews "$TA" "{\"productId\":\"$P1\",\"orderId\":\"$ORDER\",\"rating\":5,\"title\":\"x\"}"; check "product not in order" "400 PRODUCT_NOT_PURCHASED" "$STATUS $(field code)"
call POST /api/v1/reviews "$TA" "{\"productId\":\"$P3\",\"orderId\":\"$ORDER\",\"rating\":9,\"title\":\"x\"}"; check "invalid rating" 400 "$STATUS"
call POST /api/v1/reviews "$TA" "{\"productId\":\"$P3\",\"orderId\":\"$ORDER\",\"rating\":5,\"title\":\"Great bat\",\"comment\":\"Solid\"}"; REV=$(field id); check "verified review created" 201 "$STATUS"
call POST /api/v1/reviews "$TA" "{\"productId\":\"$P3\",\"orderId\":\"$ORDER\",\"rating\":4,\"title\":\"Again\"}"; check "duplicate review" "409 DUPLICATE_REVIEW" "$STATUS $(field code)"
call GET /api/v1/reviews/product/$P3 ""; check "pending review hidden publicly" "[]" "$BODY"
call POST /api/v1/reviews/admin/$REV/moderate "$TADM" '{"approve":true}'
call GET /api/v1/reviews/product/$P3 ""; check "approved review public (no login)" "$REV" "$(field id)"

echo "== Returns"
deliver(){ # deliver <order> -> pays nothing; walks the order's shipment to DELIVERED (admin)
  local sh; sh=$(q "select id from shipments where order_id='$1'")
  for s in PICKED_UP IN_TRANSIT OUT_FOR_DELIVERY DELIVERED; do call PUT /api/v1/shipments/admin/$sh/status "$TADM" "{\"newStatus\":\"$s\"}"; done; }
elig(){ call GET "/api/v1/orders/$2/returns/eligibility?productId=$3" "$1"; }
mkret(){ # mkret <token> <order> <product> <qty> <key>
  call POST /api/v1/returns "$1" "{\"orderId\":\"$2\",\"productId\":\"$3\",\"quantity\":$4,\"reason\":\"Damaged on arrival\"}" "Idempotency-Key: $5"; }

buy "$TA" $P1 3; pay "$TA" $ORDER $TOTAL; sleep 4
RORD=$ORDER
elig "$TA" $RORD $P1; check "paid but not delivered is not eligible" "200 false ORDER_NOT_DELIVERED" "$STATUS $(field eligible) $(field reason)"
mkret "$TA" $RORD $P1 1 ret-early; check "create return before delivery" "409 NOT_ELIGIBLE" "$STATUS $(field code)"
deliver $RORD
check "order delivered" DELIVERED "$(q "select status from orders where id='$RORD'")"
elig "$TA" $RORD $P1; check "delivered order is eligible (max 3)" "200 true ELIGIBLE 3" "$STATUS $(field eligible) $(field reason) $(field maxReturnableQuantity)"
check "return deadline is 7 days after delivery" "7 days" "$(q "select '$(field returnDeadline)'::timestamptz - '$(field deliveredAt)'::timestamptz")"
elig "$TA" $RORD $P2; check "product not in order" "200 false PRODUCT_NOT_IN_ORDER" "$STATUS $(field eligible) $(field reason)"
elig "$TB" $RORD $P1; check "B checks A's order eligibility (no IDOR)" "200 false ORDER_NOT_FOUND" "$STATUS $(field eligible) $(field reason)"
elig "" $RORD $P1; check "eligibility without token" 401 "$STATUS"

mkret "$TB" $RORD $P1 1 b-ret-1; check "B creates return on A's order" "409 NOT_ELIGIBLE" "$STATUS $(field code)"
check "B's attempt created nothing" 0 "$(q "select count(*) from return_requests where order_id='$RORD'")"
call POST /api/v1/returns "$TA" "{\"orderId\":\"$RORD\",\"productId\":\"$P1\",\"quantity\":1,\"reason\":\"no key\"}"; check "create return without Idempotency-Key" "400 VALIDATION_ERROR" "$STATUS $(field code)"
mkret "$TA" $RORD $P1 0 ret-q0; check "quantity 0 rejected" 400 "$STATUS"
mkret "$TA" $RORD $P1 -2 ret-qn; check "negative quantity rejected" 400 "$STATUS"
mkret "$TA" $RORD $P1 4 ret-q4; check "quantity above ordered rejected" "409 NOT_ELIGIBLE" "$STATUS $(field code)"
call POST /api/v1/returns "$TA" "{\"orderId\":\"$RORD\",\"productId\":\"$P1\",\"quantity\":1,\"reason\":\"\"}" "Idempotency-Key: ret-nr"; check "blank reason rejected" 400 "$STATUS"
mkret "$TA" $RORD $P2 1 ret-p2; check "return of product not in order" "409 NOT_ELIGIBLE" "$STATUS $(field code)"

mkret "$TA" $RORD $P1 1 ret-1; R1=$(field id); check "create return (qty 1)" "201 REQUESTED 1" "$STATUS $(field status) $(field quantity)"
mkret "$TA" $RORD $P1 1 ret-1; check "same Idempotency-Key returns same return" "201 $R1" "$STATUS $(field id)"
check "only one return row for the key" 1 "$(q "select count(*) from return_requests where order_id='$RORD' and idempotency_key='ret-1'")"
mkret "$TA" $RORD $P1 2 ret-1; check "key reused for different quantity" "409 IDEMPOTENCY_KEY_REUSED" "$STATUS $(field code)"
elig "$TA" $RORD $P1; check "remaining quantity after partial return" "true 2 1" "$(field eligible) $(field maxReturnableQuantity) $(field alreadyRequestedQuantity)"
mkret "$TA" $RORD $P1 3 ret-q3; check "more than remaining rejected" "409 NOT_ELIGIBLE" "$STATUS $(field code)"
mkret "$TA" $RORD $P1 2 ret-2; R2=$(field id); check "second partial return (qty 2)" "201 REQUESTED" "$STATUS $(field status)"
elig "$TA" $RORD $P1; check "all units requested" "false ALREADY_RETURNED 0" "$(field eligible) $(field reason) $(field maxReturnableQuantity)"
mkret "$TA" $RORD $P1 1 ret-3; check "return beyond ordered quantity" "409 NOT_ELIGIBLE" "$STATUS $(field code)"
check "held quantity never exceeds ordered" "3" "$(q "select coalesce(sum(quantity),0) from return_requests where order_id='$RORD' and product_id='$P1' and status in ('REQUESTED','APPROVED')")"

call GET /api/v1/returns/$R1 "$TA"; check "owner reads own return" "200 $R1" "$STATUS $(field id)"
call GET /api/v1/returns/$R1 "$TB"; check "B reads A's return (IDOR)" 403 "$STATUS"
call GET /api/v1/returns "$TB"; check "B's list excludes A's returns" "200 " "$STATUS $(echo "$BODY" | grep -oE "$R1" | head -1)"
call GET /api/v1/returns "$TA"; check "A's list contains own returns" "$R1" "$(echo "$BODY" | grep -oE "$R1" | head -1)"
call POST /api/v1/admin/returns/$R1/approve "$TA"; check "customer approves own return" 403 "$STATUS"
call POST /api/v1/admin/returns/$R1/approve "$TB"; check "other customer approves return" 403 "$STATUS"
call GET /api/v1/admin/returns "$TA"; check "customer lists admin returns" 403 "$STATUS"
call POST /api/v1/admin/returns/$R1/approve ""; check "approve without token" 401 "$STATUS"
call POST /api/v1/admin/returns/$R1/approve "$TADM" '{"note":"Looks fine"}'; check "admin approves" "200 APPROVED" "$STATUS $(field status)"
call POST /api/v1/admin/returns/$R1/approve "$TADM"; check "approve twice" 409 "$STATUS"
call POST /api/v1/admin/returns/$R1/reject "$TADM"; check "reject an approved return" 409 "$STATUS"
call POST /api/v1/admin/returns/$R2/reject "$TADM" '{"note":"Used item"}'; check "admin rejects" "200 REJECTED Used item" "$STATUS $(field status) $(field decisionNote)"
call GET /api/v1/admin/returns "$TADM"; check "admin lists returns" 200 "$STATUS"
call POST /api/v1/admin/returns/00000000-0000-0000-0000-00000000dead/approve "$TADM"; check "approve unknown return" "404 RETURN_NOT_FOUND" "$STATUS $(field code)"
elig "$TA" $RORD $P1; check "rejected return frees its quantity" "true 2" "$(field eligible) $(field maxReturnableQuantity)"
check "returns do not touch payment/refund state" "0" "$(q "select count(*) from refunds where payment_id=(select id from payments where order_id='$RORD')")"

echo "== Returns: concurrency + window"
buy "$TA" $P1 3; pay "$TA" $ORDER $TOTAL; sleep 4
CORD=$ORDER; deliver $CORD
rm -f /tmp/ret_d_*
for i in 1 2 3 4 5; do
  curl -s -o /tmp/ret_d_$i -w '%{http_code}
' -X POST "$B/api/v1/returns" -H "$J" -H "Authorization: Bearer $TA" -H "Idempotency-Key: dup-same"     -d "{\"orderId\":\"$CORD\",\"productId\":\"$P1\",\"quantity\":1,\"reason\":\"dup\"}" > /tmp/ret_d_s$i &
done
wait
check "5 concurrent retries of one key all succeed" "5" "$(cat /tmp/ret_d_s* | grep -c '^201$')"
check "...and return the same return id" "1" "$(cat /tmp/ret_d_[0-9]* | grep -oE '"id":"[^"]*"' | sort -u | wc -l | tr -d ' ')"
check "...and create exactly one row" "1" "$(q "select count(*) from return_requests where order_id='$CORD' and idempotency_key='dup-same'")"
rm -f /tmp/ret_d_* /tmp/ret_c_*
for i in 1 2 3 4 5 6 7 8; do
  curl -s -o /dev/null -w '%{http_code}
' -X POST "$B/api/v1/returns" -H "$J" -H "Authorization: Bearer $TA" -H "Idempotency-Key: conc-$i"     -d "{\"orderId\":\"$CORD\",\"productId\":\"$P1\",\"quantity\":1,\"reason\":\"race $i\"}" > /tmp/ret_c_$i &
done
wait
check "8 concurrent returns for the 2 remaining units: exactly 2 created" "2" "$(cat /tmp/ret_c_* | grep -c '^201$')"
check "the other 6 are rejected with 409" "6" "$(cat /tmp/ret_c_* | grep -c '^409$')"
check "units held equal units ordered (3)" "3 3" "$(q "select count(*)||' '||coalesce(sum(quantity),0) from return_requests where order_id='$CORD' and product_id='$P1'")"
rm -f /tmp/ret_c_*
q "update orders set updated_at = now() - interval '10 days' where id='$CORD'" >/dev/null
elig "$TA" $CORD $P1; check "expired window is not eligible" "false RETURN_WINDOW_EXPIRED" "$(field eligible) $(field reason)"
q "update return_requests set status='REJECTED' where order_id='$CORD'" >/dev/null
mkret "$TA" $CORD $P1 1 ret-late; check "create return after window" "409 NOT_ELIGIBLE" "$STATUS $(field code)"

echo "== Error contract"
call GET /api/v1/orders/not-a-uuid "$TA"; check "malformed id" "400 INVALID_PARAMETER" "$STATUS $(field code)"
call POST /api/v1/orders "$TA" '{bad'; check "malformed json" "400 MALFORMED_REQUEST" "$STATUS $(field code)"
call GET /api/v1/nope "$TA"; check "unknown route" "404 ROUTE_NOT_FOUND" "$STATUS $(field code)"
call DELETE /api/v1/orders "$TA"; check "wrong method" 405 "$STATUS"
call GET /api/v1/orders/not-a-uuid "$TA" "" "X-Correlation-Id: bad\nvalue"; check "unsafe correlation id replaced" "true" "$( [ "$(field correlationId)" != 'bad\nvalue' ] && echo true)"

echo "RESULT: $PASS passed, $FAIL failed"
