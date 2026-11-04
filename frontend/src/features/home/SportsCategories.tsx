import { useMemo } from 'react';
import { Box, Skeleton, Typography } from '@mui/material';
import { useNavigate } from 'react-router-dom';
import { useAllProducts, useCategories, useSports } from '@/hooks/useCatalog';
import { motion, shadows } from '@/design/tokens';

const slugOf = (name: string) => name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');

/**
 * EXPLORE SPORTS: one tile per sport in the catalogue, each with its own artwork (`/sports/<slug>.svg`) and the categories that sport
 * really has ("Bat • Ball • Gloves", derived from the products). Opens the Products page filtered to the sport. Nothing here is
 * specific to a sport: a new sport in the catalogue gets a tile (artwork falls back to the DSports visual).
 */
const SportsCategories = () => {
  const navigate = useNavigate();
  const { data: sports = [], isLoading } = useSports();
  const { data: products = [] } = useAllProducts();
  const { data: categories = [] } = useCategories();
  const categoryName = useMemo(() => new Map(categories.map((c) => [c.id, c.name])), [categories]);
  const tops = useMemo(() => {
    const m = new Map<string, string[]>();
    for (const p of products) {
      const name = categoryName.get(p.categoryId);
      if (!name) continue;
      const list = m.get(p.sportId) ?? [];
      if (!list.includes(name)) list.push(name);
      m.set(p.sportId, list);
    }
    return m;
  }, [products, categoryName]);

  return (
    <Box component="section" aria-label="Explore sports" data-testid="sports-categories">
      <Typography variant="h5" fontWeight={800} sx={{ mb: 1.5 }}>Explore sports</Typography>
      <Box sx={{ display: 'grid', gap: 1.5, gridAutoFlow: { xs: 'column', md: 'row' }, gridAutoColumns: { xs: '58%', sm: '34%', md: 'auto' },
        gridTemplateColumns: { md: 'repeat(6, 1fr)' }, overflowX: { xs: 'auto', md: 'visible' }, pb: { xs: 1, md: 0 }, scrollSnapType: { xs: 'x mandatory', md: 'none' } }}>
        {isLoading
          ? Array.from({ length: 6 }).map((_, i) => <Skeleton key={i} variant="rounded" height={170} />)
          : sports.map((s) => (
            <Box key={s.id} component="button" type="button" onClick={() => navigate(`/products?sport=${s.id}`)} aria-label={`Shop ${s.name}`}
              sx={{ p: 0, border: 0, cursor: 'pointer', textAlign: 'left', borderRadius: 4, overflow: 'hidden', bgcolor: 'background.paper', boxShadow: shadows.card, scrollSnapAlign: 'start',
                transition: `transform ${motion.base}, box-shadow ${motion.base}`, '&:hover, &:focus-visible': { transform: 'translateY(-4px)', boxShadow: shadows.cardHover, outline: 'none' },
                '&:hover img': { transform: 'scale(1.06)' } }}>
              <Box sx={{ position: 'relative', aspectRatio: '3 / 2', overflow: 'hidden' }}>
                <Box component="img" src={`/sports/${slugOf(s.name)}.svg`} alt="" loading="lazy" onError={(e: React.SyntheticEvent<HTMLImageElement>) => { e.currentTarget.src = '/products/placeholder.svg'; }}
                  sx={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block', transition: 'transform .4s ease' }} />
              </Box>
              <Box sx={{ p: 1.25 }}>
                <Typography variant="subtitle1" fontWeight={800} lineHeight={1.2}>{s.name}</Typography>
                <Typography variant="caption" color="text.secondary" noWrap display="block">{(tops.get(s.id) ?? []).slice(0, 3).join(' • ') || 'Shop now'}</Typography>
              </Box>
            </Box>
          ))}
      </Box>
    </Box>
  );
};

export default SportsCategories;
