# AI tool registry (65 tools)

Generated from `app/tools/build.py` by `tools/docs/tool_matrix_md.py`. The same registry is used by the in-process agent, the REST routes and the MCP server, so these gates apply to all three. `WRITE` tools never run without a single-use confirmation token bound to the caller, the tool and the arguments. The role set shown is the one accepted by the registry (the `ROLE_` prefix variants are folded together).

| Tool | Kind | Authentication | Roles allowed |
|---|---|---|---|
| `add_case_message` | WRITE | required | any signed-in user (own data only) |
| `calculate_landed_cost` | READ | required | ADMIN, CATEGORY_MANAGER, PROCUREMENT_BUYER |
| `check_return_eligibility` | READ | required | any signed-in user (own data only) |
| `compare_supplier_offers` | READ | required | ADMIN, CATEGORY_MANAGER, PROCUREMENT_BUYER |
| `create_return` | WRITE | required | any signed-in user (own data only) |
| `create_support_case` | WRITE | required | any signed-in user (own data only) |
| `eng_error_clusters` | READ | required | ADMIN |
| `eng_investigate` | READ | required | ADMIN |
| `eng_search_logs` | READ | required | ADMIN |
| `eng_service_health` | READ | required | ADMIN |
| `eng_trace` | READ | required | ADMIN |
| `explain_variance` | READ | required | ADMIN, CATEGORY_MANAGER, REGIONAL_MANAGER, SALES_PLANNER, STORE_MANAGER, SUPER_ADMIN |
| `get_cart` | READ | required | any signed-in user (own data only) |
| `get_clearance_candidates` | READ | required | ADMIN |
| `get_co_purchases` | READ | required | any signed-in user (own data only) |
| `get_customer_profile` | READ | required | any signed-in user (own data only) |
| `get_customer_support_cases` | READ | required | any signed-in user (own data only) |
| `get_data_readiness` | READ | required | ADMIN, CATEGORY_MANAGER, REGIONAL_MANAGER, SALES_PLANNER, STORE_MANAGER, SUPER_ADMIN |
| `get_demand_forecast` | READ | required | ADMIN |
| `get_event_impact` | READ | required | ADMIN |
| `get_events` | READ | required | ADMIN |
| `get_forecast_accuracy` | READ | required | ADMIN, CATEGORY_MANAGER, REGIONAL_MANAGER, SALES_PLANNER, STORE_MANAGER, SUPER_ADMIN |
| `get_forecast_quality` | READ | required | ADMIN |
| `get_inventory` | READ | required | ADMIN |
| `get_inventory_by_region` | READ | required | ADMIN |
| `get_inventory_history` | READ | required | ADMIN |
| `get_inventory_overview` | READ | required | ADMIN |
| `get_inventory_policy` | READ | required | ADMIN |
| `get_inventory_recommendations` | READ | required | ADMIN, CATEGORY_MANAGER, REGIONAL_MANAGER, SALES_PLANNER, STORE_MANAGER, SUPER_ADMIN |
| `get_inventory_snapshot` | READ | required | ADMIN |
| `get_opportunity_evidence` | READ | required | ADMIN, CATEGORY_MANAGER, PROCUREMENT_BUYER |
| `get_opportunity_score` | READ | required | ADMIN, CATEGORY_MANAGER, PROCUREMENT_BUYER |
| `get_order` | READ | required | any signed-in user (own data only) |
| `get_orders` | READ | required | any signed-in user (own data only) |
| `get_overstock_products` | READ | required | ADMIN |
| `get_payment_status` | READ | required | any signed-in user (own data only) |
| `get_plan_vs_actual` | READ | required | ADMIN, CATEGORY_MANAGER, REGIONAL_MANAGER, SALES_PLANNER, STORE_MANAGER, SUPER_ADMIN |
| `get_planning_analysis` | READ | required | ADMIN, CATEGORY_MANAGER, REGIONAL_MANAGER, SALES_PLANNER, STORE_MANAGER, SUPER_ADMIN |
| `get_planning_overview` | READ | required | ADMIN, CATEGORY_MANAGER, REGIONAL_MANAGER, SALES_PLANNER, STORE_MANAGER, SUPER_ADMIN |
| `get_product` | READ | none | anyone (public data) |
| `get_product_knowledge` | READ | none | anyone (public data) |
| `get_product_reviews` | READ | none | anyone (public data) |
| `get_refund_status` | READ | required | any signed-in user (own data only) |
| `get_regional_analysis` | READ | required | ADMIN, CATEGORY_MANAGER, REGIONAL_MANAGER, SALES_PLANNER, STORE_MANAGER, SUPER_ADMIN |
| `get_replenishment_recommendation` | READ | required | ADMIN |
| `get_return_status` | READ | required | any signed-in user (own data only) |
| `get_sales_history` | READ | required | ADMIN |
| `get_scenarios_and_pilots` | READ | required | ADMIN, CATEGORY_MANAGER, REGIONAL_MANAGER, SALES_PLANNER, STORE_MANAGER, SUPER_ADMIN |
| `get_shopper_profile` | READ | required | any signed-in user (own data only) |
| `get_slow_moving_products` | READ | required | ADMIN |
| `get_sourcing_overview` | READ | required | ADMIN, CATEGORY_MANAGER, PROCUREMENT_BUYER |
| `get_stock_risk` | READ | required | ADMIN |
| `get_store_hierarchy` | READ | required | ADMIN, CATEGORY_MANAGER, REGIONAL_MANAGER, SALES_PLANNER, STORE_MANAGER, SUPER_ADMIN |
| `get_support_case` | READ | required | any signed-in user (own data only) |
| `get_unmet_demand` | READ | required | ADMIN, CATEGORY_MANAGER, PROCUREMENT_BUYER |
| `get_weekly_forecast` | READ | required | ADMIN, CATEGORY_MANAGER, REGIONAL_MANAGER, SALES_PLANNER, STORE_MANAGER, SUPER_ADMIN |
| `reopen_support_case` | WRITE | required | any signed-in user (own data only) |
| `reorder_product` | WRITE | required | any signed-in user (own data only) |
| `request_case_escalation` | WRITE | required | any signed-in user (own data only) |
| `run_promotion_scenario` | READ | required | ADMIN |
| `search_product_opportunities` | READ | required | ADMIN, CATEGORY_MANAGER, PROCUREMENT_BUYER |
| `search_products` | READ | none | anyone (public data) |
| `search_support_policy` | READ | none | anyone (public data) |
| `simulate_planning_scenario` | READ | required | ADMIN, CATEGORY_MANAGER, REGIONAL_MANAGER, SALES_PLANNER, STORE_MANAGER, SUPER_ADMIN |
| `track_order` | READ | required | any signed-in user (own data only) |
