import { useEffect, useState } from 'react';
import {
  Badge,
  IconButton,
  Menu,
  MenuItem,
  Typography,
  Box,
  Divider,
  Button,
  ListItemIcon,
  ListItemText,
} from '@mui/material';
import {
  NotificationsOutlined,
  CheckCircleOutline,
  ErrorOutline,
  InfoOutlined,
  LocalShippingOutlined,
  ReceiptLongOutlined,
  CancelOutlined,
  PaymentOutlined,
} from '@mui/icons-material';
import { useNavigate } from 'react-router-dom';
import { useNotificationStore } from '@/store/notificationStore';
import { useAuthStore } from '@/store/authStore';

const typeIcon: Record<string, React.ReactNode> = {
  ORDER_PLACED: <ReceiptLongOutlined fontSize="small" />,
  PAYMENT_SUCCESS: <CheckCircleOutline fontSize="small" color="success" />,
  PAYMENT_FAILED: <ErrorOutline fontSize="small" color="error" />,
  ORDER_CONFIRMED: <CheckCircleOutline fontSize="small" color="success" />,
  ORDER_SHIPPED: <LocalShippingOutlined fontSize="small" />,
  ORDER_DELIVERED: <CheckCircleOutline fontSize="small" color="success" />,
  ORDER_CANCELLED: <CancelOutlined fontSize="small" color="error" />,
  REFUND_COMPLETED: <PaymentOutlined fontSize="small" />,
};

const NotificationCenter = () => {
  const navigate = useNavigate();
  const { isAuthenticated } = useAuthStore();
  const { notifications, unreadCount, fetchHistory, loading } = useNotificationStore();
  const [anchorEl, setAnchorEl] = useState<null | HTMLElement>(null);

  useEffect(() => {
    if (isAuthenticated) {
      fetchHistory(0, 5);
    }
  }, [isAuthenticated, fetchHistory]);

  if (!isAuthenticated) return null;

  return (
    <>
      <IconButton
        color="inherit"
        aria-label="notifications"
        onClick={(e) => setAnchorEl(e.currentTarget)}
      >
        <Badge badgeContent={unreadCount} color="error">
          <NotificationsOutlined />
        </Badge>
      </IconButton>
      <Menu
        anchorEl={anchorEl}
        open={Boolean(anchorEl)}
        onClose={() => setAnchorEl(null)}
        transformOrigin={{ horizontal: 'right', vertical: 'top' }}
        anchorOrigin={{ horizontal: 'right', vertical: 'bottom' }}
        slotProps={{ paper: { sx: { width: 360, maxHeight: 480 } } }}
      >
        <Box sx={{ px: 2, py: 1 }}>
          <Typography variant="subtitle1" fontWeight={600}>
            Notifications
          </Typography>
        </Box>
        <Divider />
        {loading ? (
          <MenuItem disabled>
            <Typography variant="body2" color="text.secondary">
              Loading...
            </Typography>
          </MenuItem>
        ) : notifications.length === 0 ? (
          <MenuItem disabled>
            <Typography variant="body2" color="text.secondary">
              No notifications yet
            </Typography>
          </MenuItem>
        ) : (
          notifications.slice(0, 5).map((n) => (
            <MenuItem
              key={n.id}
              onClick={() => {
                setAnchorEl(null);
                navigate(`/notifications/${n.id}`);
              }}
              sx={{ whiteSpace: 'normal' }}
            >
              <ListItemIcon>{typeIcon[n.type] || <InfoOutlined fontSize="small" />}</ListItemIcon>
              <ListItemText
                primary={n.subject || n.type.replace(/_/g, ' ')}
                secondary={n.message.length > 60 ? n.message.substring(0, 60) + '...' : n.message}
                primaryTypographyProps={{ variant: 'body2', fontWeight: n.status === 'SENT' ? 400 : 600 }}
                secondaryTypographyProps={{ variant: 'caption' }}
              />
            </MenuItem>
          ))
        )}
        <Divider />
        <Box sx={{ px: 1, py: 0.5 }}>
          <Button
            fullWidth
            size="small"
            onClick={() => {
              setAnchorEl(null);
              navigate('/notifications');
            }}
          >
            View All
          </Button>
        </Box>
      </Menu>
    </>
  );
};

export default NotificationCenter;
