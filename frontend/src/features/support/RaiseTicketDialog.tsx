import { useState } from 'react';
import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ordersApi } from '@/api/orders';
import { supportApi, supportError, TICKET_CATEGORIES } from '@/api/support';

interface Props { open: boolean; onClose: () => void; onCreated: (caseNumber: string) => void }

const newKey = () => `web-${crypto.randomUUID()}`;

/**
 * Customer form to raise a ticket. Two steps (fill in, then review and submit). The customer is never sent: the backend takes it from
 * the token. The order list comes from the customer's own orders. Evidence upload is not offered because the platform has no file store.
 */
const RaiseTicketDialog = ({ open, onClose, onCreated }: Props) => {
  const qc = useQueryClient();
  const [step, setStep] = useState<'edit' | 'review'>('edit');
  const [category, setCategory] = useState('');
  const [subject, setSubject] = useState('');
  const [description, setDescription] = useState('');
  const [orderId, setOrderId] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [key] = useState(newKey);

  const orders = useQuery({ queryKey: ['orders', 'mine'], queryFn: ordersApi.getOrders, enabled: open, retry: false });
  const create = useMutation({
    mutationFn: () => supportApi.create({ category, subject: subject.trim(), description: description.trim(), orderId: orderId || undefined }, key),
    onSuccess: (r) => { void qc.invalidateQueries({ queryKey: ['support'] }); onCreated(r.supportCase.caseNumber); },
    onError: (e) => setError(supportError(e).message),
  });

  const valid = category !== '' && subject.trim().length > 0 && description.trim().length > 0;
  const label = TICKET_CATEGORIES.find((c) => c.value === category)?.label;
  const order = orders.data?.find((o) => o.id === orderId);

  return (
    <Dialog open={open} onClose={create.isPending ? undefined : onClose} fullWidth maxWidth="sm" data-testid="raise-ticket-dialog">
      <DialogTitle>{step === 'edit' ? 'Raise a ticket' : 'Review your ticket'}</DialogTitle>
      <DialogContent>
        {step === 'edit' ? (
          <Stack spacing={2} sx={{ pt: 1 }}>
            <TextField select label="Category" value={category} onChange={(e) => setCategory(e.target.value)} required inputProps={{ 'data-testid': 'ticket-category' }}>
              {TICKET_CATEGORIES.map((c) => <MenuItem key={c.value} value={c.value}>{c.label}</MenuItem>)}
            </TextField>
            <TextField label="Subject" value={subject} onChange={(e) => setSubject(e.target.value)} required inputProps={{ maxLength: 200, 'data-testid': 'ticket-subject' }} />
            <TextField label="Describe the problem" value={description} onChange={(e) => setDescription(e.target.value)} required multiline minRows={4}
              inputProps={{ maxLength: 2000, 'data-testid': 'ticket-description' }} helperText={`${description.length}/2000`} />
            <TextField select label="Related order (optional)" value={orderId} onChange={(e) => setOrderId(e.target.value)} disabled={orders.isLoading}
              inputProps={{ 'data-testid': 'ticket-order' }} helperText={orders.isError ? 'Your orders could not be loaded; you can still submit without one.' : undefined}>
              <MenuItem value="">No specific order</MenuItem>
              {(orders.data ?? []).map((o) => <MenuItem key={o.id} value={o.id}>{o.orderNumber} · {o.status}</MenuItem>)}
            </TextField>
            <Typography variant="caption" color="text.secondary">Attachments are not supported yet; describe what you see and support can ask for more.</Typography>
          </Stack>
        ) : (
          <Stack spacing={1} sx={{ pt: 1 }} data-testid="ticket-review">
            <Typography variant="body2"><b>Category:</b> {label}</Typography>
            <Typography variant="body2"><b>Subject:</b> {subject}</Typography>
            <Typography variant="body2"><b>Order:</b> {order ? order.orderNumber : 'None'}</Typography>
            <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>{description}</Typography>
            {error && <Alert severity="warning" data-testid="ticket-error">{error}</Alert>}
          </Stack>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={create.isPending}>Cancel</Button>
        {step === 'edit'
          ? <Button variant="contained" disabled={!valid} onClick={() => setStep('review')} data-testid="ticket-review-btn">Review</Button>
          : (
            <>
              <Button onClick={() => { setError(null); setStep('edit'); }} disabled={create.isPending}>Back</Button>
              <Button variant="contained" disabled={create.isPending} onClick={() => { setError(null); create.mutate(); }} data-testid="ticket-submit">Submit ticket</Button>
            </>
          )}
      </DialogActions>
    </Dialog>
  );
};

export default RaiseTicketDialog;
