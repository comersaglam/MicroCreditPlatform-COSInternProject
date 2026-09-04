import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { clearToken } from '../api/client';

const NAV = [
  { group: 'Kayıtlar', items: [
    { to: '/buyers', label: 'Alıcılar' },
    { to: '/sellers', label: 'Satıcılar' },
  ]},
  { group: 'Analiz', items: [
    { to: '/stats/buyers', label: 'Alıcı istatistikleri' },
    { to: '/stats/sellers', label: 'Satıcı istatistikleri' },
    { to: '/traffic', label: 'Trafik' },
  ]},
  { group: 'Sistem', items: [
    { to: '/profile', label: 'Admin profili' },
  ]},
];

export function Shell() {
  const navigate = useNavigate();

  return (
    <div className="shell">
      <nav className="sidebar">
        <div className="brand">
          <img src="/logo-fides.png" alt="" />
          <div>
            <div className="brand-name">T-Fides</div>
            <div className="brand-role">Admin</div>
          </div>
        </div>

        {NAV.map((section) => (
          <div key={section.group}>
            <div className="nav-group">{section.group}</div>
            {section.items.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}
              >
                {item.label}
              </NavLink>
            ))}
          </div>
        ))}

        <div className="nav-group">Yakında</div>
        {/* Turn 50. Shown rather than hidden so the roadmap is visible in the demo; not a
            link, because there is nothing behind it yet. */}
        <span className="nav-item disabled" aria-disabled="true">
          Asistan ⏳
        </span>

        <div style={{ marginTop: 24, padding: '0 8px' }}>
          <button
            onClick={() => {
              clearToken();
              navigate('/login');
            }}
          >
            Çıkış
          </button>
        </div>
      </nav>

      <main className="content">
        <Outlet />
      </main>
    </div>
  );
}
