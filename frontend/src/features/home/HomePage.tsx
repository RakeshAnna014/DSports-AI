import { useCallback, useMemo } from 'react';
import { Box, Container, Stack } from '@mui/material';
import Hero from '@/features/home/Hero';
import FeaturedProducts from '@/features/home/FeaturedProducts';
import ProductShelf, { diversify, type ShelfData } from '@/features/home/ProductShelf';
import SportsCategories from '@/features/home/SportsCategories';
import AiShowcase from '@/features/home/AiShowcase';
import { AiSearchResults } from '@/features/ai/AiCommerceSearch';
import RecommendationSection from '@/components/products/RecommendationSection';
import PersonalizedSection from '@/features/home/PersonalizedSection';
import { useAuthStore } from '@/store/authStore';
import { useCartStore } from '@/store/cartStore';
import { useRecentStore } from '@/store/recentStore';

/** The best real discounts: price vs list price from the pricing API, never invented. */
const bestDeals = ({ products, priceById }: ShelfData) => {
  const discount = (id: string) => { const pr = priceById.get(id); return pr && pr.mrp > pr.sellingPrice ? (pr.mrp - pr.sellingPrice) / pr.mrp : 0; };
  const deals = products.filter((p) => discount(p.id) > 0).sort((a, b) => discount(b.id) - discount(a.id));
  return diversify(deals, 2);   // the best deal of each sport first, so the shelf is not one sport
};

/**
 * Home: a dark hero with the AI search as the centrepiece, then bright product areas. AI answers appear in the page right under the hero;
 * the normal storefront (trending, deals, sports) is always there and works without the AI service.
 */
const HomePage = () => {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const cartItems = useCartStore((s) => s.items);
  const cartIds = useMemo(() => cartItems.map((i) => i.productId), [cartItems]);
  const recentIds = useRecentStore((s) => s.ids);
  const recent = useCallback(
    ({ products }: ShelfData) => recentIds.map((id) => products.find((p) => p.id === id)).filter((p): p is NonNullable<typeof p> => !!p),
    [recentIds],
  );

  return (
    <Box>
      <Hero />
      <Container maxWidth="lg" sx={{ py: { xs: 3, md: 5 } }}>
        <Stack spacing={{ xs: 4, md: 6 }}>
          <AiSearchResults />
          <SportsCategories />
          {isAuthenticated && <PersonalizedSection />}
          <FeaturedProducts />
          <ProductShelf title="Best deals" subtitle="The biggest real savings against list price, across sports" testId="deals" pick={bestDeals}
            action={{ label: 'All products', to: '/products' }} />
          {cartIds.length > 0 && <RecommendationSection productIds={cartIds} title="Complete your setup" limit={4} />}
          {recentIds.length > 0 && <ProductShelf title="Recently viewed" testId="recently-viewed" pick={recent} />}
          <AiShowcase />
        </Stack>
      </Container>
    </Box>
  );
};

export default HomePage;
