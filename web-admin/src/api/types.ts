/*
 * The admin API's shapes, mirroring backend/app/routers/admin.py.
 *
 * Hand-written rather than generated, and deliberately NOT derived from
 * shared-contracts/openapi.yaml: that contract is what three clients agree on, and none of
 * these shapes is spoken by a phone or a till. Only this panel reads them.
 *
 * Money is integer kuruş everywhere -- `*_minor` -- exactly as it is on the wire and in
 * the database. Nothing here divides by 100 except the formatter.
 */

export interface AdminSession {
  token: string;
  expires_at: string;
}

export interface SellerRow {
  user_id: string;
  display_name: string;
  shop_name: string | null;
  phone: string;
  customer_count: number;
  entry_count: number;
  receivable_minor: number;
  created_at: string;
}

export interface BuyerRow {
  user_id: string;
  display_name: string;
  phone: string;
  is_seller: boolean;
  shop_count: number;
  debt_minor: number;
  created_at: string;
}

export interface Entry {
  transaction_id: string;
  seller_id: string;
  customer_id: string;
  counterparty: string;
  amount_minor: number;
  type: 'DEBT' | 'PAYMENT' | 'INDEXATION';
  description: string | null;
  created_at: string;
}

export interface CustomerRow {
  customer_id: string;
  display_name: string;
  phone: string;
  claim_status: 'CLAIMED' | 'UNCLAIMED';
  balance_minor: number;
}

export interface FxSnapshot {
  as_of: string;
  usd_minor: number;
  eur_minor: number;
  gold_minor: number;
  cpi_index: number;
}

/** principal + indexation - paid === outstanding, always. See backend/app/breakdown.py. */
export interface Breakdown {
  principal_minor: number;
  indexation_minor: number;
  total_paid_minor: number;
  outstanding_minor: number;
  fx_at_open?: FxSnapshot | null;
  fx_today?: FxSnapshot | null;
  projected_3m_minor?: number | null;
}

export interface ShopDebt {
  seller_id: string;
  shop_name: string | null;
  display_name: string;
  balance_minor: number;
}

export interface SellerDetail {
  seller: SellerRow;
  breakdown: Breakdown;
  customers: CustomerRow[];
  recent_entries: Entry[];
}

export interface BuyerDetail {
  buyer: BuyerRow;
  breakdown: Breakdown;
  debts_by_shop: ShopDebt[];
  recent_entries: Entry[];
}

export interface MonthPoint {
  month: string;
  debt_minor: number;
  payment_minor: number;
  indexation_minor: number;
  entry_count: number;
  /** The month the data stops inside -- a few days, not thirty. Excluded from the charts;
      plotting it draws a cliff that reads as a collapse. */
  partial: boolean;
}

/** A label with a number. In debt_bands the number is a COUNT OF PEOPLE, not money. */
export interface NamedAmount {
  label: string;
  amount_minor: number;
}

export interface SellerStats {
  seller_count: number;
  customer_count: number;
  total_receivable_minor: number;
  breakdown: Breakdown;
  monthly: MonthPoint[];
  top_sellers: NamedAmount[];
  riskiest_customers: NamedAmount[];
  collection_rate: number;
  data_through: string | null;
}

export interface BuyerStats {
  buyer_count: number;
  claimed_customer_count: number;
  unclaimed_customer_count: number;
  total_debt_minor: number;
  breakdown: Breakdown;
  monthly: MonthPoint[];
  top_debtors: NamedAmount[];
  by_category: NamedAmount[];
  debt_bands: NamedAmount[];
  data_through: string | null;
}

export interface InventoryItem {
  name: string;
  status: 'REAL' | 'MOCK' | 'MISSING';
  note: string;
}

export interface AdminMe {
  subject: string;
  expires_in_seconds: number;
  counts: Record<string, number>;
  migration_head: string | null;
  core_seeded: boolean;
  demo_seeded: boolean;
  data_through: string | null;
  inventory: InventoryItem[];
}
