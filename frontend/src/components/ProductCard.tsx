import { useState } from 'react';
import {
  Card,
  CardMedia,
  CardContent,
  CardActions,
  Typography,
  Button,
  IconButton,
  Box,
  Chip,
} from '@mui/material';
import {
  FavoriteBorder,
  Favorite,
  ShoppingCartOutlined,
  VisibilityOutlined,
} from '@mui/icons-material';
import type { ProductSummary, Price, InventoryRecord } from '@/types/catalog';
import {
  getProductImageUrl,
  formatPrice,
  computeDiscount,
  computeStockLevel,
} from '@/lib/productUtils';

interface ProductCardProps {
  product: ProductSummary;
  brandName?: string;
  categoryName?: string;
  price?: Price;
  inventory?: InventoryRecord[];
  onViewDetails: () => void;
  onAddToCart?: () => void;
}

const ProductCard = ({
  product,
  brandName,
  categoryName,
  price,
  inventory,
  onViewDetails,
  onAddToCart,
}: ProductCardProps) => {
  const [wishlisted, setWishlisted] = useState(false);
  const stock = computeStockLevel(inventory);
  const imgUrl = getProductImageUrl(product.name);
  const discount = price ? computeDiscount(price.mrp, price.sellingPrice) : 0;
  const isOutOfStock = stock.level === 'out_of_stock';

  return (
    <Card
      tabIndex={0}
      sx={{
        display: 'flex',
        flexDirection: 'column',
        borderRadius: 2,
        boxShadow: '0 1px 3px rgba(0,0,0,0.08)',
        transition: 'transform 0.2s, box-shadow 0.2s',
        '&:hover': {
          transform: 'translateY(-4px)',
          boxShadow: '0 8px 25px rgba(0,0,0,0.12)',
        },
        '&:focus-visible': {
          outline: '2px solid',
          outlineColor: 'primary.main',
        },
        height: '100%',
      }}
    >
      <Box sx={{ position: 'relative' }}>
        <CardMedia
          component="img"
          image={imgUrl}
          alt={product.name}
          loading="lazy"
          sx={{
            height: 220,
            objectFit: 'cover',
            bgcolor: 'grey.100',
          }}
        />
        <IconButton
          aria-label={wishlisted ? 'Remove from wishlist' : 'Add to wishlist'}
          onClick={(e) => {
            e.stopPropagation();
            setWishlisted((prev) => !prev);
          }}
          sx={{
            position: 'absolute',
            top: 4,
            right: 4,
            bgcolor: 'rgba(255,255,255,0.9)',
            '&:hover': { bgcolor: 'white' },
          }}
          size="small"
        >
          {wishlisted ? (
            <Favorite fontSize="small" color="error" />
          ) : (
            <FavoriteBorder fontSize="small" />
          )}
        </IconButton>
        {discount > 0 && (
          <Chip
            label={`-${discount}%`}
            color="error"
            size="small"
            sx={{
              position: 'absolute',
              top: 8,
              left: 8,
              fontWeight: 700,
              fontSize: '0.75rem',
            }}
          />
        )}
      </Box>

      <CardContent sx={{ flexGrow: 1, pb: 0 }}>
        {brandName && (
          <Typography variant="caption" color="text.secondary" display="block" gutterBottom>
            {brandName}
          </Typography>
        )}
        <Typography
          variant="subtitle2"
          fontWeight={600}
          lineHeight={1.3}
          sx={{
            display: '-webkit-box',
            WebkitLineClamp: 2,
            WebkitBoxOrient: 'vertical',
            overflow: 'hidden',
            minHeight: 40,
          }}
        >
          {product.name}
        </Typography>
        <Typography variant="caption" color="text.disabled" display="block" sx={{ mt: 0.5 }}>
          SKU: {product.sku}
        </Typography>

        {price && (
          <Box sx={{ mt: 1, display: 'flex', alignItems: 'baseline', gap: 1 }}>
            <Typography variant="h6" color="primary.main" fontWeight={700}>
              {formatPrice(price.sellingPrice, price.currency)}
            </Typography>
            {price.mrp > price.sellingPrice && (
              <Typography
                variant="body2"
                color="text.disabled"
                sx={{ textDecoration: 'line-through' }}
              >
                {formatPrice(price.mrp, price.currency)}
              </Typography>
            )}
          </Box>
        )}

        <Box sx={{ mt: 0.5 }}>
          <Chip
            label={stock.label}
            size="small"
            variant="outlined"
            color={
              stock.level === 'in_stock'
                ? 'success'
                : stock.level === 'low_stock'
                  ? 'warning'
                  : 'error'
            }
            sx={{ fontSize: '0.7rem', fontWeight: 600 }}
          />
        </Box>
      </CardContent>

      <CardActions sx={{ p: 2, pt: 1, gap: 1 }}>
        <Button
          variant="outlined"
          size="small"
          startIcon={<VisibilityOutlined />}
          onClick={onViewDetails}
          fullWidth
        >
          View Details
        </Button>
        <Button
          variant="contained"
          size="small"
          disabled={isOutOfStock}
          onClick={(e) => {
            e.stopPropagation();
            onAddToCart?.();
          }}
          sx={{ minWidth: 44, px: 1 }}
          aria-label={isOutOfStock ? 'Out of stock' : 'Add to cart'}
        >
          <ShoppingCartOutlined fontSize="small" />
        </Button>
      </CardActions>
    </Card>
  );
};

export default ProductCard;
