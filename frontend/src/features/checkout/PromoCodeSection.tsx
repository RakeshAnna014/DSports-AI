import { useCallback, useEffect, useState } from 'react';
import { Alert, Box, Button, Card, CardContent, Chip, CircularProgress, Stack, TextField, Typography } from '@mui/material';
import { LocalOfferOutlined, AutoAwesome } from '@mui/icons-material';
import { checkoutApi } from '@/api/checkout';
import { aiApi } from '@/api/ai';
import { getApiErrorMessage } from '@/api/errors';
import { formatPrice } from '@/lib/productUtils';
import type { CheckoutResponse, OfferRecommendations, PromotionOffer, RecommendedOffer } from '@/types/checkout';

interface Props {
  checkout: CheckoutResponse;
  /** True while another checkout action is running. */
  busy: boolean;
  onChange: (checkout: CheckoutResponse) => void;
}

/** The backend's own list, shaped like the AI recommendation, used when the AI service cannot be reached. */
const fromBackend = (offers: PromotionOffer[]): OfferRecommendations => ({
  checkoutId: '',
  personalized: false,
  personalizationOff: false,
  basedOn: [],
  message: offers.some((o) => o.eligible) ? null : 'No offer applies to this cart right now.',
  offers: offers.map((o) => ({ ...o, reasons: o.eligible ? [`Saves ${formatPrice(Number(o.discountAmount))} on this cart`] : [], personal: false })),
  disclaimer: 'Offers marked DEMO are synthetic demonstration offers, not real promotions. The discount is calculated by the DSports backend.',
});

const PromoCodeSection = ({ checkout, busy, onChange }: Props) => {
  const editable = checkout.status === 'PENDING';
  const [code, setCode] = useState('');
  const [working, setWorking] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [recs, setRecs] = useState<OfferRecommendations | null>(null);
  const [aiUnavailable, setAiUnavailable] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      setRecs(await aiApi.recommendOffers(checkout.id));
      setAiUnavailable(false);
    } catch {
      // The AI service is optional: the backend's own list still gives the customer every eligible offer.
      try {
        setRecs(fromBackend(await checkoutApi.listPromotions(checkout.id)));
        setAiUnavailable(true);
      } catch (err) {
        setRecs(null);
        setLoadError(getApiErrorMessage(err, 'Offers could not be loaded. You can still enter a code.'));
      }
    } finally {
      setLoading(false);
    }
  }, [checkout.id]);

  useEffect(() => {
    load();
  }, [load, checkout.promotionCode, checkout.subtotal]);

  const apply = async (raw: string) => {
    const value = raw.trim();
    if (!value) {
      setError('Enter a promo code.');
      return;
    }
    setWorking(true);
    setError(null);
    setNotice(null);
    try {
      const updated = await checkoutApi.applyPromotion(checkout.id, value);
      onChange(updated);
      setCode('');
      setNotice(`${updated.promotionCode} applied: you save ${formatPrice(updated.discountAmount)}.`);
    } catch (err) {
      setError(getApiErrorMessage(err, 'The code could not be applied. Check your connection and try again.'));
    } finally {
      setWorking(false);
    }
  };

  const remove = async () => {
    setWorking(true);
    setError(null);
    setNotice(null);
    try {
      onChange(await checkoutApi.removePromotion(checkout.id));
      setNotice('Promo code removed.');
    } catch (err) {
      setError(getApiErrorMessage(err, 'The code could not be removed. Try again.'));
    } finally {
      setWorking(false);
    }
  };

  const disabled = working || busy || !editable;
  const offers: RecommendedOffer[] = recs?.offers ?? [];

  return (
    <Card variant="outlined" sx={{ borderRadius: 2 }} data-testid="promo-section">
      <CardContent>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
          <LocalOfferOutlined color="primary" />
          <Typography variant="h6" fontWeight={600}>Offers and promo code</Typography>
        </Box>

        {checkout.promotionCode ? (
          <Alert
            severity="success"
            sx={{ mb: 2 }}
            data-testid="promo-applied"
            action={editable ? <Button color="inherit" size="small" onClick={remove} disabled={disabled}>Remove</Button> : undefined}
          >
            <strong>{checkout.promotionCode}</strong> applied: you save {formatPrice(checkout.discountAmount)}.
            {!editable && ' Promotions cannot be changed after the checkout is validated.'}
          </Alert>
        ) : (
          <Box component="form" sx={{ display: 'flex', gap: 1, mb: 2 }} noValidate onSubmit={(e) => { e.preventDefault(); apply(code); }}>
            <TextField
              size="small"
              label="Promo code"
              value={code}
              onChange={(e) => { setCode(e.target.value.toUpperCase()); setError(null); }}
              disabled={disabled}
              fullWidth
              slotProps={{ htmlInput: { maxLength: 40, 'aria-label': 'Promo code', autoCapitalize: 'characters' } }}
            />
            <Button type="submit" variant="outlined" disabled={disabled || !code.trim()}>
              {working ? <CircularProgress size={20} aria-label="Applying" /> : 'Apply'}
            </Button>
          </Box>
        )}

        {error && <Alert severity="error" sx={{ mb: 2 }} role="alert">{error}</Alert>}
        {notice && !error && <Alert severity="success" sx={{ mb: 2 }}>{notice}</Alert>}

        {loading ? (
          <Box sx={{ display: 'flex', justifyContent: 'center', py: 2 }}><CircularProgress size={24} aria-label="Loading offers" /></Box>
        ) : loadError ? (
          <Alert severity="warning" action={<Button color="inherit" size="small" onClick={load}>Retry</Button>}>{loadError}</Alert>
        ) : (
          <>
            {aiUnavailable && (
              <Alert severity="info" sx={{ mb: 2 }}>
                Personalised suggestions are unavailable right now. These are the offers DSports has for this cart.
              </Alert>
            )}
            {recs?.message && !checkout.promotionCode && <Alert severity="info" sx={{ mb: 2 }} data-testid="promo-none">{recs.message}</Alert>}
            {offers.length > 0 && (
              <Stack spacing={1.5} data-testid="promo-offers">
                {offers.map((o) => (
                  <Box key={o.code} data-testid={`offer-${o.code}`} sx={{ border: '1px solid', borderColor: o.recommended ? 'primary.main' : 'divider', borderRadius: 1.5, p: 1.5, opacity: o.eligible ? 1 : 0.75 }}>
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
                      <Typography fontWeight={700}>{o.title}</Typography>
                      <Chip size="small" label={o.code} variant="outlined" />
                      {o.demo && <Chip size="small" label="DEMO" color="warning" variant="outlined" />}
                      {o.recommended && <Chip size="small" icon={<AutoAwesome />} label={o.personal ? 'Recommended for you' : 'Best saving'} color="primary" />}
                      <Box sx={{ flexGrow: 1 }} />
                      {o.eligible && <Typography fontWeight={700} color="success.main">Save {formatPrice(Number(o.discountAmount))}</Typography>}
                    </Box>
                    <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>{o.description}</Typography>
                    {o.reasons.length > 0 && (
                      <Box component="ul" sx={{ m: 0, mt: 0.5, pl: 2.5 }}>
                        {o.reasons.map((r) => <Typography key={r} component="li" variant="caption" color="text.secondary">{r}</Typography>)}
                      </Box>
                    )}
                    {!o.eligible && o.ineligibleReason && <Typography variant="caption" color="warning.main" display="block" sx={{ mt: 0.5 }}>{o.ineligibleReason}</Typography>}
                    {o.eligible && !o.applied && editable && !checkout.promotionCode && (
                      <Button size="small" variant="contained" sx={{ mt: 1 }} disabled={disabled} onClick={() => apply(o.code)}>Apply {o.code}</Button>
                    )}
                    {o.applied && <Chip size="small" color="success" label="Applied" sx={{ mt: 1 }} />}
                  </Box>
                ))}
              </Stack>
            )}
            {recs && offers.length > 0 && (
              <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 1.5 }}>
                {recs.basedOn.length > 0 && `Based on ${recs.basedOn.join(', ')}. `}
                {recs.personalizationOff && 'Personalisation is off for this profile. '}
                {recs.disclaimer}
              </Typography>
            )}
          </>
        )}
      </CardContent>
    </Card>
  );
};

export default PromoCodeSection;
