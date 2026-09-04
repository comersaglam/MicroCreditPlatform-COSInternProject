import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import {
  BrowserRouter,
  Navigate,
  Route,
  Routes,
} from 'react-router-dom';

import './theme.css';
import { storedToken } from './api/client';
import { Shell } from './components/Shell';
import { Login } from './pages/Login';
import { Buyers } from './pages/Buyers';
import { BuyerDetail } from './pages/BuyerDetail';
import { Sellers } from './pages/Sellers';
import { SellerDetail } from './pages/SellerDetail';
import { BuyerStats } from './pages/BuyerStats';
import { SellerStats } from './pages/SellerStats';
import { Traffic } from './pages/Traffic';
import { AdminProfile } from './pages/AdminProfile';

/**
 * The gate. Read at render rather than held in context on purpose: client.ts clears the
 * token the moment any request comes back 401, so the next render after an expired session
 * lands here and redirects -- without a second mechanism that could disagree with it.
 */
function RequireAdmin({ children }: { children: React.ReactNode }) {
  return storedToken() ? <>{children}</> : <Navigate to="/login" replace />;
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route
          element={
            <RequireAdmin>
              <Shell />
            </RequireAdmin>
          }
        >
          <Route path="/" element={<Navigate to="/buyers" replace />} />
          <Route path="/buyers" element={<Buyers />} />
          <Route path="/buyers/:userId" element={<BuyerDetail />} />
          <Route path="/sellers" element={<Sellers />} />
          <Route path="/sellers/:userId" element={<SellerDetail />} />
          <Route path="/stats/buyers" element={<BuyerStats />} />
          <Route path="/stats/sellers" element={<SellerStats />} />
          <Route path="/traffic" element={<Traffic />} />
          <Route path="/profile" element={<AdminProfile />} />
        </Route>
        <Route path="*" element={<Navigate to="/buyers" replace />} />
      </Routes>
    </BrowserRouter>
  </StrictMode>,
);
