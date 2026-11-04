import { createBrowserRouter } from 'react-router-dom';
import MainLayout from '@/layouts/MainLayout';
import HomePage from '@/features/home/HomePage';
import LoginPage from '@/features/auth/LoginPage';
import RegisterPage from '@/features/auth/RegisterPage';
import ProductsPage from '@/features/products/ProductsPage';
import ProductDetailPage from '@/features/products/ProductDetailPage';
import ProfilePage from '@/features/profile/ProfilePage';
import CartPage from '@/features/cart/CartPage';
import WishlistPage from '@/features/wishlist/WishlistPage';
import CheckoutPage from '@/features/checkout/CheckoutPage';
import OrdersPage from '@/features/orders/OrdersPage';
import OrderDetailPage from '@/features/orders/OrderDetailPage';
import OrderSuccessPage from '@/features/orders/OrderSuccessPage';
import PaymentPage from '@/features/payments/PaymentPage';
import PaymentSuccessPage from '@/features/payments/PaymentSuccessPage';
import PaymentFailurePage from '@/features/payments/PaymentFailurePage';
import PaymentHistoryPage from '@/features/payments/PaymentHistoryPage';
import NotificationHistoryPage from '@/features/notifications/NotificationHistoryPage';
import NotificationDetailPage from '@/features/notifications/NotificationDetailPage';
import ShipmentPage from '@/features/shipments/ShipmentPage';
import TrackingPage from '@/features/shipments/TrackingPage';
import NotFoundPage from '@/features/not-found/NotFoundPage';
import AiShopPage from '@/features/ai/AiShopPage';
import ReturnsPage from '@/features/returns/ReturnsPage';
import InventoryApp from '@/features/inventory/InventoryApp';
import EngineeringApp from '@/features/engineering/EngineeringApp';
import SupportApp from '@/features/support/SupportApp';
import FulfilmentApp from '@/features/fulfilment/FulfilmentApp';
import SourcingApp from '@/features/sourcing/SourcingApp';
import PlanningApp from '@/features/planning/PlanningApp';
import MyCasesPage from '@/features/support/MyCasesPage';

const router = createBrowserRouter([
  // Internal, business-facing area: its own layout (no storefront chrome). Access is enforced by the backend (ADMIN role) on every request.
  { path: '/inventory-ai/:tab?', element: <InventoryApp /> },
  { path: '/engineering-ai', element: <EngineeringApp /> },
  // Internal buyer area (Product & Supplier Intelligence): access is enforced by the AI service (buyer roles; approvals need an approver) on every request.
  { path: '/product-intelligence', element: <SourcingApp /> },
  // Internal planning area (Sales & Inventory Planning): access is enforced by the AI service (planning roles, approvals, per-user store scope) on every request.
  { path: '/sales-planning', element: <PlanningApp /> },
  // Internal demo fulfilment console (ADMIN, enforced by the backend on every request): advances paid orders through delivery for Return AI demos.
  { path: '/fulfilment', element: <FulfilmentApp /> },
  // Internal support console: its own layout. Access is enforced by the backend (SUPPORT_EXECUTIVE or ADMIN) on every request.
  { path: '/support-cases/:id?', element: <SupportApp /> },
  {
    element: <MainLayout />,
    children: [
      { path: '/', element: <HomePage /> },
      { path: '/login', element: <LoginPage /> },
      { path: '/register', element: <RegisterPage /> },
      { path: '/products', element: <ProductsPage /> },
      { path: '/products/:id', element: <ProductDetailPage /> },
      { path: '/profile', element: <ProfilePage /> },
      { path: '/cart', element: <CartPage /> },
      { path: '/wishlist', element: <WishlistPage /> },
      { path: '/checkout', element: <CheckoutPage /> },
      { path: '/ai', element: <AiShopPage /> },
      { path: '/returns', element: <ReturnsPage /> },
      { path: '/support/:ref?', element: <MyCasesPage /> },
      { path: '/orders', element: <OrdersPage /> },
      { path: '/orders/:id', element: <OrderDetailPage /> },
      { path: '/order-success', element: <OrderSuccessPage /> },
      { path: '/payment', element: <PaymentPage /> },
      { path: '/payment-success', element: <PaymentSuccessPage /> },
      { path: '/payment-failure', element: <PaymentFailurePage /> },
      { path: '/notifications', element: <NotificationHistoryPage /> },
      { path: '/notifications/:id', element: <NotificationDetailPage /> },
      { path: '/payments', element: <PaymentHistoryPage /> },
      { path: '/shipments/:id', element: <ShipmentPage /> },
      { path: '/track', element: <TrackingPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]);

export default router;
