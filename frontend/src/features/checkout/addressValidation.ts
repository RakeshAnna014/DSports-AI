/**
 * Client-side mirror of the backend address rules (identity domain: AddressLine, State, PostalCode, Country), so a customer
 * sees a precise message before any request is sent. The backend stays authoritative and re-validates everything.
 */
export interface NewAddressForm {
  line1: string;
  line2: string;
  city: string;
  state: string;
  country: string;
  postalCode: string;
}

export type AddressField = keyof NewAddressForm;
export type AddressErrors = Partial<Record<AddressField, string>>;

/** The backend accepts any ISO 3166-1 alpha-2 code; these are the countries the storefront offers. */
export const COUNTRIES: { code: string; name: string }[] = [
  { code: 'IN', name: 'India' },
  { code: 'US', name: 'United States' },
  { code: 'GB', name: 'United Kingdom' },
  { code: 'AE', name: 'United Arab Emirates' },
  { code: 'SG', name: 'Singapore' },
  { code: 'AU', name: 'Australia' },
];

export const EMPTY_ADDRESS: NewAddressForm = { line1: '', line2: '', city: '', state: '', country: 'IN', postalCode: '' };

export const validateAddress = (a: NewAddressForm): AddressErrors => {
  const errors: AddressErrors = {};
  const line1 = a.line1.trim();
  if (!line1) errors.line1 = 'Enter the street address';
  else if (line1.length > 255) errors.line1 = 'Use at most 255 characters';
  if (a.line2.trim().length > 255) errors.line2 = 'Use at most 255 characters';
  if (!a.city.trim()) errors.city = 'Enter the city';
  else if (a.city.trim().length > 100) errors.city = 'Use at most 100 characters';
  if (!a.state.trim()) errors.state = 'Enter the state';
  else if (a.state.trim().length > 100) errors.state = 'Use at most 100 characters';
  const postal = a.postalCode.trim();
  if (!postal) errors.postalCode = 'Enter the postal code';
  else if (!/^[a-zA-Z0-9]+$/.test(postal)) errors.postalCode = 'Use letters and digits only, without spaces or dashes';
  else if (postal.length > 20) errors.postalCode = 'Use at most 20 characters';
  if (!/^[A-Z]{2}$/.test(a.country.trim())) errors.country = 'Choose a country';
  return errors;
};

/** The request body: trimmed values, optional line 2 omitted, always a shipping address. */
export const toAddressRequest = (a: NewAddressForm) => ({
  line1: a.line1.trim(),
  line2: a.line2.trim() || undefined,
  city: a.city.trim(),
  state: a.state.trim(),
  country: a.country.trim(),
  postalCode: a.postalCode.trim(),
  type: 'SHIPPING' as const,
});
