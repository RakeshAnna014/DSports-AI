import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Box,
  Typography,
  Paper,
  Chip,
  Button,
  CircularProgress,
  Alert,
  Divider,
  Grid,
} from '@mui/material';
import {
  ArrowBackOutlined,
  ReplayOutlined,
  CheckCircleOutline,
  ErrorOutline,
} from '@mui/icons-material';
import { notificationsApi, type NotificationResult } from '@/api/notifications';
import { getApiErrorMessage } from '@/api/errors';
import { useAuthStore } from '@/store/authStore';

const NotificationDetailPage = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { user } = useAuthStore();
  const [notification, setNotification] = useState<NotificationResult | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [retrying, setRetrying] = useState(false);

  const isAdmin = user?.roles?.includes('ROLE_ADMIN');

  useEffect(() => {
    if (!id) return;
    setLoading(true);
    notificationsApi
      .getById(id)
      .then((data) => {
        setNotification(data);
        setLoading(false);
      })
      .catch((err) => {
        setError(err.response?.data?.message || 'Failed to load notification');
        setLoading(false);
      });
  }, [id]);

  const handleRetry = async () => {
    if (!notification) return;
    setRetrying(true);
    try {
      const updated = await notificationsApi.retry(notification.id);
      setNotification(updated);
    } catch (err) {
      setError(getApiErrorMessage(err, 'Retry failed'));
    } finally {
      setRetrying(false);
    }
  };

  if (loading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}>
        <CircularProgress />
      </Box>
    );
  }

  if (error && !notification) {
    return <Alert severity="error">{error}</Alert>;
  }

  if (!notification) {
    return <Alert severity="warning">Notification not found.</Alert>;
  }

  const statusColor =
    notification.status === 'SENT'
      ? 'success'
      : notification.status === 'FAILED'
        ? 'error'
        : notification.status === 'RETRYING'
          ? 'warning'
          : 'default';

  return (
    <Box>
      <Button startIcon={<ArrowBackOutlined />} onClick={() => navigate('/notifications')} sx={{ mb: 2 }}>
        Back to Notifications
      </Button>

      <Paper sx={{ p: 3 }}>
        <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', mb: 2 }}>
          <Box>
            <Typography variant="h5" fontWeight={600}>
              {notification.subject || notification.type.replace(/_/g, ' ')}
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
              {new Date(notification.createdAt).toLocaleString()}
            </Typography>
          </Box>
          <Chip
            label={notification.status}
            color={statusColor}
            icon={
              notification.status === 'SENT' ? (
                <CheckCircleOutline />
              ) : notification.status === 'FAILED' ? (
                <ErrorOutline />
              ) : undefined
            }
          />
        </Box>

        <Divider sx={{ my: 2 }} />

        <Grid container spacing={2}>
          <Grid item xs={6}>
            <Typography variant="caption" color="text.secondary">
              Type
            </Typography>
            <Typography variant="body2">{notification.type.replace(/_/g, ' ')}</Typography>
          </Grid>
          <Grid item xs={6}>
            <Typography variant="caption" color="text.secondary">
              Channel
            </Typography>
            <Typography variant="body2">{notification.channel}</Typography>
          </Grid>
          <Grid item xs={6}>
            <Typography variant="caption" color="text.secondary">
              Retry Count
            </Typography>
            <Typography variant="body2">
              {notification.retryCount} / {notification.maxRetries}
            </Typography>
          </Grid>
          <Grid item xs={6}>
            <Typography variant="caption" color="text.secondary">
              Reference
            </Typography>
            <Typography variant="body2">
              {notification.referenceType ? `${notification.referenceType}: ${notification.referenceId}` : 'N/A'}
            </Typography>
          </Grid>
        </Grid>

        <Divider sx={{ my: 2 }} />

        <Typography variant="caption" color="text.secondary">
          Message
        </Typography>
        <Typography variant="body1" sx={{ mt: 0.5, whiteSpace: 'pre-wrap' }}>
          {notification.message}
        </Typography>

        {notification.failureReason && (
          <>
            <Divider sx={{ my: 2 }} />
            <Typography variant="caption" color="error">
              Failure Reason
            </Typography>
            <Typography variant="body2" color="error">
              {notification.failureReason}
            </Typography>
          </>
        )}

        {isAdmin && notification.status === 'FAILED' && notification.retryCount < notification.maxRetries && (
          <Box sx={{ mt: 3 }}>
            <Button
              variant="contained"
              startIcon={<ReplayOutlined />}
              onClick={handleRetry}
              disabled={retrying}
            >
              {retrying ? 'Retrying...' : 'Retry Notification'}
            </Button>
          </Box>
        )}

        {error && (
          <Alert severity="error" sx={{ mt: 2 }}>
            {error}
          </Alert>
        )}
      </Paper>
    </Box>
  );
};

export default NotificationDetailPage;
