import { useState } from 'react';
import { Box, IconButton } from '@mui/material';
import { ChevronLeft, ChevronRight } from '@mui/icons-material';
import { getProductImageUrl } from '@/lib/productUtils';

interface ImageGalleryProps {
  productName: string;
  images?: { url: string; primary?: boolean }[];
}

const ImageGallery = ({ productName, images = [] }: ImageGalleryProps) => {
  const allUrls =
    images.length > 0
      ? images.map((img) => img.url)
      : [getProductImageUrl(productName)];
  const [activeIndex, setActiveIndex] = useState(0);
  const hasMultiple = allUrls.length > 1;

  return (
    <Box>
      <Box
        sx={{
          position: 'relative',
          borderRadius: 2,
          overflow: 'hidden',
          bgcolor: 'grey.100',
          mb: 1,
        }}
      >
        <Box
          component="img"
          src={allUrls[activeIndex]}
          alt={`${productName} - Image ${activeIndex + 1}`}
          loading="lazy"
          sx={{
            width: '100%',
            height: { xs: 300, sm: 400, md: 480 },
            objectFit: 'contain',
            display: 'block',
          }}
        />
        {hasMultiple && (
          <>
            <IconButton
              aria-label="Previous image"
              onClick={() =>
                setActiveIndex((prev) =>
                  prev === 0 ? allUrls.length - 1 : prev - 1,
                )
              }
              sx={{
                position: 'absolute',
                left: 8,
                top: '50%',
                transform: 'translateY(-50%)',
                bgcolor: 'rgba(255,255,255,0.9)',
                '&:hover': { bgcolor: 'white' },
              }}
            >
              <ChevronLeft />
            </IconButton>
            <IconButton
              aria-label="Next image"
              onClick={() =>
                setActiveIndex((prev) =>
                  prev === allUrls.length - 1 ? 0 : prev + 1,
                )
              }
              sx={{
                position: 'absolute',
                right: 8,
                top: '50%',
                transform: 'translateY(-50%)',
                bgcolor: 'rgba(255,255,255,0.9)',
                '&:hover': { bgcolor: 'white' },
              }}
            >
              <ChevronRight />
            </IconButton>
          </>
        )}
      </Box>

      {hasMultiple && (
        <Box sx={{ display: 'flex', gap: 1, overflowX: 'auto', pb: 0.5 }}>
          {allUrls.map((url, index) => (
            <Box
              key={index}
              component="img"
              src={url}
              alt={`${productName} thumbnail ${index + 1}`}
              loading="lazy"
              onClick={() => setActiveIndex(index)}
              role="button"
              tabIndex={0}
              onKeyDown={(e: React.KeyboardEvent) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  setActiveIndex(index);
                }
              }}
              sx={{
                width: 64,
                height: 64,
                objectFit: 'cover',
                borderRadius: 1,
                cursor: 'pointer',
                border: '2px solid',
                borderColor:
                  index === activeIndex ? 'primary.main' : 'transparent',
                opacity: index === activeIndex ? 1 : 0.6,
                transition: 'opacity 0.2s, border-color 0.2s',
                '&:hover': { opacity: 1 },
                flexShrink: 0,
              }}
            />
          ))}
        </Box>
      )}
    </Box>
  );
};

export default ImageGallery;
