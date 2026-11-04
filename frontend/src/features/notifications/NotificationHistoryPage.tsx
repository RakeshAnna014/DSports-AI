import { useEffect, useState } from 'react';
import {
  Box,
  Typography,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Chip,
  IconButton,
  Button,
  Pagination,
  Tooltip,
  Alert,
} from '@mui/material';
import {
  RefreshOutlined,
  VisibilityOutlined,
  ReplayOutlined,
} from '@mui/icons-material';
import { useNavigate } from 'react-router-dom';
import { useNotificationStore } from '@/store/notificationStore';
import { useAuthStore } from '@/store/authStore';

const statusChip = (status: string) => {
  const map: Record<string, { label: string; color: 'success' | 'error' | 'warning' | 'default' }> = {
    PENDING: { label: 'Pending', color: 'default' },
    SENT: { label: 'Sent', color: 'success' },
    FAILED: { label: 'Failed', color: 'error' },
    RETRYING: { label: 'Retrying', color: 'warning' },
  };
  const c = map[status] || { label: status, color: 'default' };
  return <Chip label={c.label} color={c.color} size="small" />;
};

const typeLabel = (type: string) =>
  type
    .replace(/_/g, ' ')
    .toLowerCase()
    .replace(/\b\w/g, (c) => c.toUpperCase());

const NotificationHistoryPage = () => {
  const navigate = useNavigate();
  const { user } = useAuthStore();
  const { notifications, totalCount, loading, fetchHistory, retryNotification } = useNotificationStore();
  const [page, setPage] = useState(0);
  const [retryingId, setRetryingId] = useState<string | null>(null);
  const size = 20;

  useEffect(() => {
    fetchHistory(page, size);
  }, [page, fetchHistory]);

  const isAdmin = user?.roles?.includes('ROLE_ADMIN');
  const totalPages = Math.ceil(totalCount / size);

  const handleRetry = async (id: string) => {
    setRetryingId(id);
    try {
      await retryNotification(id);
    } finally {
      setRetryingId(null);
    }
  };

  return (
    <Box>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3 }}>
        <Typography variant="h5" fontWeight={600}>
          Notifications
        </Typography>
        <Button
          startIcon={<RefreshOutlined />}
          onClick={() => fetchHistory(page, size)}
          disabled={loading}
        >
          Refresh
        </Button>
      </Box>

      {notifications.length === 0 && !loading ? (
        <Alert severity="info">No notifications found.</Alert>
      ) : (
        <TableContainer component={Paper}>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Type</TableCell>
                <TableCell>Subject</TableCell>
                <TableCell>Channel</TableCell>
                <TableCell>Status</TableCell>
                <TableCell>Date</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {notifications.map((n) => (
                <TableRow key={n.id} hover>
                  <TableCell>
                    <Tooltip title={n.type}>
                      <span>{typeLabel(n.type)}</span>
                    </Tooltip>
                  </TableCell>
                  <TableCell>
                    {n.subject || typeLabel(n.type)}
                    {n.failureReason && (
                      <Typography variant="caption" display="block" color="error">
                        {n.failureReason}
                      </Typography>
                    )}
                  </TableCell>
                  <TableCell>
                    <Chip label={n.channel} size="small" variant="outlined" />
                  </TableCell>
                  <TableCell>{statusChip(n.status)}</TableCell>
                  <TableCell>
                    <Typography variant="body2" color="text.secondary">
                      {new Date(n.createdAt).toLocaleDateString()}
                    </Typography>
                  </TableCell>
                  <TableCell align="right">
                    <Tooltip title="View Details">
                      <IconButton
                        size="small"
                        onClick={() => navigate(`/notifications/${n.id}`)}
                      >
                        <VisibilityOutlined fontSize="small" />
                      </IconButton>
                    </Tooltip>
                    {isAdmin && n.status === 'FAILED' && n.retryCount < n.maxRetries && (
                      <Tooltip title="Retry">
                        <IconButton
                          size="small"
                          onClick={() => handleRetry(n.id)}
                          disabled={retryingId === n.id}
                        >
                          <ReplayOutlined fontSize="small" />
                        </IconButton>
                      </Tooltip>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      {totalPages > 1 && (
        <Box sx={{ display: 'flex', justifyContent: 'center', mt: 3 }}>
          <Pagination
            count={totalPages}
            page={page + 1}
            onChange={(_, p) => setPage(p - 1)}
            color="primary"
          />
        </Box>
      )}
    </Box>
  );
};

export default NotificationHistoryPage;
