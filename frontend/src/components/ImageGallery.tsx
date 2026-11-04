import { useState } from 'react';
import { Box, IconButton } from '@mui/material';
import { ChevronLeft, ChevronRight } from '@mui/icons-material';
import ProductImage from '@/components/common/ProductImage';
import { colors } from '@/design/tokens';

interface ImageGalleryProps {
  productName: string;
  images?: { url: string; primary?: boolean }[];
}

/** A large product image with a thumbnail strip; the primary image comes first. Every image falls back to the DSports visual if it fails. */
const ImageGallery = ({ productName, images = [] }: ImageGalleryProps) => {
  const ordered = [...images].sort((a, b) => Number(!!b.primary) - Number(!!a.primary));
  const urls = ordered.length > 0 ? ordered.map((i) => i.url) : [undefined];
  const [active, setActive] = useState(0);
  const multiple = urls.length > 1;
  const step = (d: number) => setActive((i) => (i + d + urls.length) % urls.length);

  return (
    <Box data-testid="image-gallery">
      <Box sx={{ position: 'relative', borderRadius: 4, overflow: 'hidden', border: `1px solid ${colors.line}`, mb: 1.25 }}>
        <ProductImage key={urls[active] ?? 'none'} src={urls[active]} alt={`${productName} - image ${active + 1} of ${urls.length}`} eager />
        {multiple && (
          <>
            <IconButton aria-label="Previous image" onClick={() => step(-1)} sx={{ position: 'absolute', left: 8, top: '50%', transform: 'translateY(-50%)', bgcolor: 'rgba(255,255,255,0.92)', '&:hover': { bgcolor: '#fff' } }}><ChevronLeft /></IconButton>
            <IconButton aria-label="Next image" onClick={() => step(1)} sx={{ position: 'absolute', right: 8, top: '50%', transform: 'translateY(-50%)', bgcolor: 'rgba(255,255,255,0.92)', '&:hover': { bgcolor: '#fff' } }}><ChevronRight /></IconButton>
          </>
        )}
      </Box>
      {multiple && (
        <Box sx={{ display: 'flex', gap: 1, overflowX: 'auto', pb: 0.5 }} role="tablist" aria-label="Product images">
          {urls.map((url, index) => (
            <Box key={`${url}-${index}`} role="tab" aria-selected={index === active} aria-label={`Show image ${index + 1}`} tabIndex={0} data-testid="gallery-thumb"
              onClick={() => setActive(index)} onKeyDown={(e: React.KeyboardEvent) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setActive(index); } }}
              sx={{ width: 76, flexShrink: 0, cursor: 'pointer', borderRadius: 2, overflow: 'hidden', border: '2px solid', borderColor: index === active ? 'primary.main' : colors.line,
                opacity: index === active ? 1 : 0.7, transition: 'opacity .2s, border-color .2s', '&:hover, &:focus-visible': { opacity: 1, outline: 'none', borderColor: 'primary.light' } }}>
              <ProductImage src={url} alt={`${productName} thumbnail ${index + 1}`} />
            </Box>
          ))}
        </Box>
      )}
    </Box>
  );
};

export default ImageGallery;
