"""Creates a REAL customer with a delivered order (through the DSports APIs) for the browser journey, and prints it as JSON.
Usage: python e2e/ai/ai_ui_setup.py   ->   {"email": ..., "password": ..., "orderNumber": ..., "orderId": ...}"""
import json
import stack as s

s.restock()
email, token = s.register("ui")
admin_email, _ = s.register("uiadm")
order = s.delivered_order(token, s.make_admin(admin_email), s.P_SHOE_DSC, 1)
print(json.dumps({"email": email, "password": s.PASSWORD, "orderNumber": order["orderNumber"], "orderId": order["id"], "userToken": token}))
