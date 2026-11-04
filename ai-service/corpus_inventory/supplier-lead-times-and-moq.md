# Supplier Lead Times and Minimum Order Quantities (SAMPLE internal document)

This is a SAMPLE document written for the DSports AI proof of concept. The suppliers, lead times and quantities are POC reference data,
not real supplier agreements.

## Supplier by sport and typical lead time

| Sport | Supplier | Typical lead time |
|---|---|---|
| Badminton | Meerut Sports Works | 12 days (individual SKUs vary by about 2 days) |
| Basketball | Bengaluru Sports Imports | 16 days (individual SKUs vary by about 2 days) |
| Cricket | Bengaluru Sports Imports | 16 days (individual SKUs vary by about 2 days) |
| Cycling | Bengaluru Sports Imports | 16 days (individual SKUs vary by about 2 days) |
| Fitness | Delhi Equipment House | 6 days (individual SKUs vary by about 2 days) |
| Football | Mumbai Apparel Mills | 8 days (individual SKUs vary by about 2 days) |
| Running | Guangzhou Sports Trading | 24 days (individual SKUs vary by about 2 days) |
| Swimming | Jalandhar Athletics Co | 9 days (individual SKUs vary by about 2 days) |
| Table Tennis | Jalandhar Athletics Co | 9 days (individual SKUs vary by about 2 days) |
| Tennis | Delhi Equipment House | 6 days (individual SKUs vary by about 2 days) |
| Volleyball | Meerut Sports Works | 12 days (individual SKUs vary by about 2 days) |

## Minimum order quantity (MOQ) and case packs

- Products priced below INR 5,000: the MOQ is 6, 12, 24 or 48 units depending on the SKU, and they ship in case packs of 6.
- Products priced at INR 5,000 or more (bats, bicycles, premium shoes): the MOQ is 2, 4 or 6 units and there is no case pack.
- The exact MOQ, case pack and lead time of a SKU are held in the inventory master data; the replenishment recommendation always rounds up to them.
- A purchase order quantity is raised to the MOQ even when the calculated need is smaller, and then rounded up to a whole number of case packs.

## Late deliveries

If an open purchase order will arrive after the projected stock-out date, the buyer asks the supplier to expedite it before raising a new order.
