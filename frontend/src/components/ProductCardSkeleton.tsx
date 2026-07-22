import { Card, CardContent, Skeleton, Box } from '@mui/material';

const ProductCardSkeleton = () => (
  <Card
    sx={{
      borderRadius: 2,
      boxShadow: '0 1px 3px rgba(0,0,0,0.08)',
      height: '100%',
    }}
  >
    <Skeleton variant="rectangular" height={220} sx={{ bgcolor: 'grey.100' }} />
    <CardContent>
      <Skeleton variant="text" width="40%" height={16} />
      <Skeleton variant="text" width="90%" height={20} />
      <Skeleton variant="text" width="30%" height={14} sx={{ mt: 0.5 }} />
      <Box sx={{ mt: 1, display: 'flex', gap: 1 }}>
        <Skeleton variant="text" width="35%" height={28} />
        <Skeleton variant="text" width="35%" height={28} />
      </Box>
      <Skeleton variant="rounded" width="30%" height={24} sx={{ mt: 1 }} />
    </CardContent>
    <Box sx={{ p: 2, pt: 0, display: 'flex', gap: 1 }}>
      <Skeleton variant="rounded" width="70%" height={36} />
      <Skeleton variant="rounded" width="30%" height={36} />
    </Box>
  </Card>
);

export default ProductCardSkeleton;
