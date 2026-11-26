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
import VenuesPage from '@/features/venues/VenuesPage';
import VenueDetailPage from '@/features/venues/VenueDetailPage';
import BookingPaymentPage from '@/features/venues/BookingPaymentPage';
import MyBookingsPage from '@/features/venues/MyBookingsPage';
import OwnerVenuesPage from '@/features/venues/OwnerVenuesPage';
import TeamsPage from '@/features/cricket/TeamsPage';
import TeamDetailPage from '@/features/cricket/TeamDetailPage';
import PlayersPage from '@/features/cricket/PlayersPage';
import MatchesPage from '@/features/cricket/MatchesPage';
import NewMatchPage from '@/features/cricket/NewMatchPage';
import MatchSetupPage from '@/features/cricket/MatchSetupPage';
import ScorerPage from '@/features/cricket/scoring/ScorerPage';
import PublicScorecardPage from '@/features/cricket/public/PublicScorecardPage';
import PlayerStatsPage from '@/features/cricket/public/PlayerStatsPage';
import LiveMatchesPage from '@/features/cricket/public/LiveMatchesPage';
import AdminGroundOwnersPage from '@/features/admin/AdminGroundOwnersPage';
import AdminVenuesPage from '@/features/admin/AdminVenuesPage';
import AdminSportsPage from '@/features/admin/AdminSportsPage';
import RefusedMessagesPage from '@/features/admin/RefusedMessagesPage';

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
      { path: '/venues', element: <VenuesPage /> },
      { path: '/venues/:id', element: <VenueDetailPage /> },
      { path: '/bookings', element: <MyBookingsPage /> },
      { path: '/bookings/:id/pay', element: <BookingPaymentPage /> },
      { path: '/owner/venues', element: <OwnerVenuesPage /> },
      { path: '/teams', element: <TeamsPage /> },
      { path: '/teams/:id', element: <TeamDetailPage /> },
      { path: '/players', element: <PlayersPage /> },
      // Public, no sign-in: a started match's scorecard (the link people share) and the list of matches being played.
      { path: '/m/:id', element: <PublicScorecardPage /> },
      { path: '/live', element: <LiveMatchesPage /> },
      { path: '/p/:id', element: <PlayerStatsPage /> },
      { path: '/matches', element: <MatchesPage /> },
      { path: '/matches/new', element: <NewMatchPage /> },
      { path: '/matches/:id/setup', element: <MatchSetupPage /> },
      { path: '/matches/:id/score', element: <ScorerPage /> },
      { path: '/admin/ground-owners', element: <AdminGroundOwnersPage /> },
      { path: '/admin/venues', element: <AdminVenuesPage /> },
      { path: '/admin/sports', element: <AdminSportsPage /> },
      { path: '/admin/refused-messages', element: <RefusedMessagesPage /> },
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
