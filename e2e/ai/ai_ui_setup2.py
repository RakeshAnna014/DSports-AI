"""A REAL customer with TWO delivered orders (shoes, then the MRF bat) through the DSports APIs, for the redesign journey.
Usage: python e2e/ai/ai_ui_setup2.py -> JSON with the credentials and both order numbers."""
import json
import stack as s

s.restock()
email, token = s.register("ux2")
admin_email, _ = s.register("ux2adm")
admin = s.make_admin(admin_email)
shoes = s.delivered_order(token, admin, s.P_SHOE_DSC, 1)
bat = s.delivered_order(token, admin, s.P_BAT_MRF, 1)
print(json.dumps({"email": email, "password": s.PASSWORD, "shoesOrder": shoes["orderNumber"], "batOrder": bat["orderNumber"], "batOrderId": bat["id"]}))
