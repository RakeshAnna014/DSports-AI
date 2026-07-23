import { createBrowserRouter } from 'react-router-dom';
import MainLayout from '@/layouts/MainLayout';
import HomePage from '@/features/home/HomePage';
import LoginPage from '@/features/auth/LoginPage';
import RegisterPage from '@/features/auth/RegisterPage';
import ProductsPage from '@/features/products/ProductsPage';
import ProductDetailPage from '@/features/products/ProductDetailPage';
import ProfilePage from '@/features/profile/ProfilePage';
import CartPage from '@/features/cart/CartPage';
import CheckoutPage from '@/features/checkout/CheckoutPage';
import NotFoundPage from '@/features/not-found/NotFoundPage';

const router = createBrowserRouter([
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
      { path: '/checkout', element: <CheckoutPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]);

export default router;
