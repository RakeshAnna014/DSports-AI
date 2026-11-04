"""Creates a business (ADMIN) account and a normal customer through the real DSports APIs for the inventory browser test.
Prints JSON {adminEmail, customerEmail, password}. The admin role is granted the same way the other E2E helpers do (database update)."""
import json
import sys

import stack as s

admin_email, _ = s.register("invadmin")
s.make_admin(admin_email)
customer_email, _ = s.register("invcustomer")
print(json.dumps({"adminEmail": admin_email, "customerEmail": customer_email, "password": s.PASSWORD}))
sys.exit(0)
