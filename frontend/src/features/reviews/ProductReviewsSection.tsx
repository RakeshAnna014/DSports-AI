import { useCallback, useEffect, useState } from 'react';
import {
  Box,
  Typography,
  Rating,
  Divider,
  Paper,
  Stack,
  Chip,
  IconButton,
  Tooltip,
  Pagination,
  Alert,
  LinearProgress,
  Button,
  TextField,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  CircularProgress,
} from '@mui/material';
import { ThumbUpOutlined, ThumbUp } from '@mui/icons-material';
import Grid from '@mui/material/Grid2';
import { reviewsApi, ReviewResult, RatingSummaryResult, ReviewEligibility } from '@/api/reviews';
import { getApiErrorMessage } from '@/api/errors';
import { useAuthStore } from '@/store/authStore';

interface Props {
  productId: string;
}

const statusChip = (status: string) => {
  const map: Record<string, { label: string; color: 'success' | 'warning' | 'error' | 'default' }> = {
    APPROVED: { label: 'Approved', color: 'success' },
    PENDING: { label: 'Pending', color: 'warning' },
    REJECTED: { label: 'Rejected', color: 'error' },
    HIDDEN: { label: 'Hidden', color: 'default' },
  };
  const c = map[status] || { label: status, color: 'default' };
  return <Chip label={c.label} color={c.color} size="small" variant="outlined" />;
};

const ProductReviewsSection = ({ productId }: Props) => {
  const { user } = useAuthStore();
  const [reviews, setReviews] = useState<ReviewResult[]>([]);
  const [summary, setSummary] = useState<RatingSummaryResult | null>(null);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [voteLoading, setVoteLoading] = useState<string | null>(null);
  const [eligibility, setEligibility] = useState<ReviewEligibility | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const size = 10;

  useEffect(() => {
    if (!user) {
      setEligibility(null);
      return;
    }
    reviewsApi
      .getEligibility(productId)
      .then(setEligibility)
      .catch(() => setEligibility(null));
  }, [user, productId]);

  const loadReviews = useCallback(async () => {
    setLoading(true);
    try {
      const data = await reviewsApi.getProductReviews(productId, page, size);
      setReviews(data);
    } catch {
      setReviews([]);
    } finally {
      setLoading(false);
    }
  }, [productId, page]);

  const loadSummary = useCallback(async () => {
    try {
      const data = await reviewsApi.getRatingSummary(productId);
      setSummary(data);
    } catch {
      setSummary(null);
    }
  }, [productId]);

  useEffect(() => {
    loadReviews();
    loadSummary();
  }, [loadReviews, loadSummary]);

  const handleVoteHelpful = async (reviewId: string) => {
    setVoteLoading(reviewId);
    setMessage(null);
    try {
      await reviewsApi.voteHelpful(reviewId);
      await loadReviews();
    } catch (err) {
      setMessage(getApiErrorMessage(err, 'Could not record your vote'));
    } finally {
      setVoteLoading(null);
    }
  };

  const ratingBars = summary
    ? [5, 4, 3, 2, 1].map((star) => {
        const count =
          star === 5
            ? summary.rating5Count
            : star === 4
              ? summary.rating4Count
              : star === 3
                ? summary.rating3Count
                : star === 2
                  ? summary.rating2Count
                  : summary.rating1Count;
        const pct = summary.totalReviews > 0 ? (count / summary.totalReviews) * 100 : 0;
        return { star, count, pct };
      })
    : [];

  return (
    <Box>
      <Divider sx={{ mb: 3 }} />
      <Typography variant="h5" fontWeight={600} gutterBottom>
        Customer Reviews
      </Typography>

      {summary && (
        <Paper variant="outlined" sx={{ p: 3, mb: 3 }}>
          <Grid container spacing={3} alignItems="center">
            <Grid size={{ xs: 12, md: 4 }} sx={{ textAlign: 'center' }}>
              <Typography variant="h2" fontWeight={700} color="primary.main">
                {summary.averageRating.toFixed(1)}
              </Typography>
              <Rating value={Math.round(summary.averageRating)} precision={0.5} readOnly />
              <Typography variant="body2" color="text.secondary">
                {summary.totalReviews} review{summary.totalReviews !== 1 ? 's' : ''}
              </Typography>
            </Grid>
            <Grid size={{ xs: 12, md: 8 }}>
              <Stack spacing={0.5}>
                {ratingBars.map(({ star, count, pct }) => (
                  <Box key={star} sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                    <Typography variant="body2" sx={{ minWidth: 12 }}>
                      {star}
                    </Typography>
                    <Rating value={1} max={1} readOnly size="small" />
                    <LinearProgress
                      variant="determinate"
                      value={pct}
                      sx={{ flexGrow: 1, height: 8, borderRadius: 4 }}
                    />
                    <Typography variant="body2" color="text.secondary" sx={{ minWidth: 30 }}>
                      {count}
                    </Typography>
                  </Box>
                ))}
              </Stack>
            </Grid>
          </Grid>
        </Paper>
      )}

      {message && (
        <Alert severity="warning" sx={{ mb: 2 }} onClose={() => setMessage(null)}>
          {message}
        </Alert>
      )}

      {user && eligibility?.eligible && (
        <Button variant="contained" onClick={() => setDialogOpen(true)} sx={{ mb: 2 }}>
          Write a Review
        </Button>
      )}
      {user && eligibility?.reason === 'ALREADY_REVIEWED' && (
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          You have reviewed this product ({eligibility.existingReviewStatus?.toLowerCase()}).
        </Typography>
      )}
      {user && eligibility?.reason === 'NOT_PURCHASED_OR_NOT_DELIVERED' && (
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          Reviews can be written once you have received this product.
        </Typography>
      )}

      {loading ? (
        <CircularProgress />
      ) : reviews.length === 0 ? (
        <Alert severity="info">No reviews yet. Be the first to review this product!</Alert>
      ) : (
        reviews.map((review) => (
          <Paper key={review.id} variant="outlined" sx={{ p: 2, mb: 2 }}>
            <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
              <Box>
                <Rating value={review.rating} readOnly size="small" />
                <Typography variant="subtitle2" fontWeight={600}>
                  {review.title}
                </Typography>
              </Box>
              {statusChip(review.status)}
            </Box>
            {review.comment && (
              <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
                {review.comment}
              </Typography>
            )}
            <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mt: 1 }}>
              <Typography variant="caption" color="text.disabled">
                {new Date(review.createdAt).toLocaleDateString()}
                {review.verifiedPurchase && (
                  <Chip label="Verified Purchase" size="small" color="success" variant="outlined" sx={{ ml: 1 }} />
                )}
              </Typography>
              <Tooltip title="Mark as helpful">
                <IconButton
                  size="small"
                  onClick={() => handleVoteHelpful(review.id)}
                  disabled={voteLoading === review.id}
                >
                  {review.helpfulCount > 0 ? (
                    <ThumbUp fontSize="small" color="primary" />
                  ) : (
                    <ThumbUpOutlined fontSize="small" />
                  )}
                </IconButton>
              </Tooltip>
            </Box>
            {review.helpfulCount > 0 && (
              <Typography variant="caption" color="text.secondary">
                {review.helpfulCount} found helpful
              </Typography>
            )}
          </Paper>
        ))
      )}

      {reviews.length >= size && (
        <Box sx={{ display: 'flex', justifyContent: 'center', mt: 2 }}>
          <Pagination count={10} page={page + 1} onChange={(_, p) => setPage(p - 1)} color="primary" />
        </Box>
      )}

      {eligibility?.orderId && (
        <CreateReviewDialog
          open={dialogOpen}
          productId={productId}
          orderId={eligibility.orderId}
          onClose={() => setDialogOpen(false)}
          onCreated={() => {
            setDialogOpen(false);
            setMessage('Thank you! Your review will appear once it has been approved.');
            setEligibility({ eligible: false, reason: 'ALREADY_REVIEWED', orderId: eligibility.orderId, existingReviewStatus: 'PENDING' });
            loadReviews();
            loadSummary();
          }}
        />
      )}
    </Box>
  );
};

const CreateReviewDialog = ({
  open,
  productId,
  orderId,
  onClose,
  onCreated,
}: {
  open: boolean;
  productId: string;
  orderId: string;
  onClose: () => void;
  onCreated: () => void;
}) => {
  const [rating, setRating] = useState<number | null>(5);
  const [title, setTitle] = useState('');
  const [comment, setComment] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async () => {
    if (!rating || !title.trim()) return;
    setSubmitting(true);
    setError(null);
    try {
      await reviewsApi.create({
        productId,
        orderId,
        rating,
        title: title.trim(),
        comment: comment.trim() || undefined,
      });
      onCreated();
    } catch (err) {
      setError(getApiErrorMessage(err, 'Your review could not be submitted'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Write a Review</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <Box>
            <Typography variant="body2" gutterBottom>
              Rating
            </Typography>
            <Rating value={rating} onChange={(_, v) => setRating(v)} />
          </Box>
          <TextField
            label="Title"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            fullWidth
            required
            size="small"
            inputProps={{ maxLength: 200 }}
          />
          <TextField
            label="Comment (optional)"
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            fullWidth
            multiline
            rows={4}
            size="small"
            inputProps={{ maxLength: 2000 }}
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          onClick={handleSubmit}
          disabled={submitting || !rating || !title.trim()}
        >
          {submitting ? 'Submitting...' : 'Submit Review'}
        </Button>
      </DialogActions>
    </Dialog>
  );
};

export default ProductReviewsSection;
