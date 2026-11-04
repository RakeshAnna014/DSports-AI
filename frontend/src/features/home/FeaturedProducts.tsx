import ProductShelf, { diversify, type ShelfData } from '@/features/home/ProductShelf';

// The default storefront shows the range of the shop: the newest products, taken round-robin across every sport.
const trending = ({ products }: ShelfData) =>
  diversify([...products].sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()), 2);

/** "Trending now": the default storefront, real products with live prices and stock (no AI involved). */
const FeaturedProducts = () => (
  <ProductShelf title="Trending now" subtitle="Popular picks from the DSports catalogue" testId="featured-products" limit={8}
    action={{ label: 'View all products', to: '/products' }} pick={trending} />
);

export default FeaturedProducts;
