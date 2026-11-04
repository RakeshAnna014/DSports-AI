import { useEffect, useState } from 'react';
import { Box, Skeleton } from '@mui/material';
import type { SxProps, Theme } from '@mui/material/styles';
import { useProductImageMap } from '@/hooks/useCatalog';
import { FALLBACK_IMAGE } from '@/lib/productUtils';

interface Props {
  /** The image URL when the caller has it. */
  src?: string | null;
  /** Otherwise the product id: the catalogue's own primary image is used (cart, orders, AI cards only know the id). */
  productId?: string;
  alt: string;
  /** `contain` keeps the whole product visible on a light studio background (never crops the product). */
  fit?: 'contain' | 'cover';
  sx?: SxProps<Theme>;
  /** The box is square by default; pass a different aspect ratio (e.g. 4/3) when needed. */
  aspectRatio?: string;
  eager?: boolean;
}

/**
 * The one way a product picture is shown: lazy-loaded, with a skeleton while loading, a DSports fallback visual when the image is missing
 * or fails (never a broken-image icon, never the product name in the picture), alt text, and a console warning in development so
 * a missing asset is noticed and fixed.
 */
const ProductImage = ({ src, productId, alt, fit = 'contain', sx, aspectRatio = '1 / 1', eager = false }: Props) => {
  const mapped = useProductImageMap();
  const wanted = src || (productId ? mapped.get(productId) : undefined) || FALLBACK_IMAGE;
  const [url, setUrl] = useState(wanted);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => { setUrl(wanted); setLoaded(false); }, [wanted]);

  const onError = () => {
    if (import.meta.env.DEV) console.warn(`[ProductImage] failed to load "${url}" for "${alt}"`);
    if (url !== FALLBACK_IMAGE) setUrl(FALLBACK_IMAGE);
  };

  return (
    <Box sx={{ position: 'relative', width: '100%', aspectRatio, overflow: 'hidden', background: 'linear-gradient(145deg, #F8FAFD 0%, #E9EFF8 100%)', ...(sx as object) }}>
      {!loaded && <Skeleton variant="rectangular" animation="wave" sx={{ position: 'absolute', inset: 0, height: '100%' }} />}
      <Box component="img" src={url} alt={alt} loading={eager ? 'eager' : 'lazy'} decoding="async" onLoad={() => setLoaded(true)} onError={onError}
        sx={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: fit, p: fit === 'contain' ? '6%' : 0, opacity: loaded ? 1 : 0, transition: 'opacity 240ms ease, transform 320ms ease' }} />
    </Box>
  );
};

export default ProductImage;
