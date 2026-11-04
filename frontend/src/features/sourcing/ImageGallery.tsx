import { safeHref } from '@/utils/safeUrl';
import { useState } from 'react';
import { Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Link, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { sourcingApi, sourcingError, type MatchLevel, type ProductImage as Img } from '@/api/sourcing';
import { MATCH_LABEL, MatchChip, ProductImage, pretty } from './parts';

const AddImage = ({ oppId, onClose, onSaved }: { oppId: string; onClose: () => void; onSaved: () => void }) => {
  const [url, setUrl] = useState('');
  const [level, setLevel] = useState<MatchLevel>('LIKELY_MATCH');
  const [note, setNote] = useState('');
  const save = useMutation({ mutationFn: () => sourcingApi.addImage(oppId, url.trim(), level, note.trim()), onSuccess: () => { onSaved(); onClose(); } });
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth data-testid="image-dialog">
      <DialogTitle>Add or correct an image</DialogTitle>
      <DialogContent><Stack spacing={1.5} sx={{ pt: 1 }}>
        <Alert severity="info" icon={false}>Only the address is stored (the picture is not copied). The host must be on the allow-list of approved image hosts, and the address is checked before it is saved. Use the manufacturer's or an authorised page and say how sure you are that it shows this exact product.</Alert>
        <TextField size="small" label="Image address (https)" value={url} onChange={(e) => setUrl(e.target.value)} inputProps={{ 'data-testid': 'image-url' }} />
        <TextField select size="small" label="How well does it match?" value={level} onChange={(e) => setLevel(e.target.value as MatchLevel)}>
          {(Object.keys(MATCH_LABEL) as MatchLevel[]).map((m) => <MenuItem key={m} value={m}>{MATCH_LABEL[m].label}</MenuItem>)}
        </TextField>
        <TextField size="small" label="Note (optional)" value={note} onChange={(e) => setNote(e.target.value)} />
        {save.isError && <Alert severity="warning" data-testid="image-error">{sourcingError(save.error)}</Alert>}
      </Stack></DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={!url.trim().startsWith('https://') || save.isPending} onClick={() => save.mutate()} data-testid="image-save">Check and add</Button></DialogActions>
    </Dialog>
  );
};

const Report = ({ image, onClose, onDone }: { image: Img; onClose: () => void; onDone: () => void }) => {
  const [reason, setReason] = useState('');
  const send = useMutation({ mutationFn: () => sourcingApi.reportImage(image.id, reason.trim()), onSuccess: () => { onDone(); onClose(); } });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs" data-testid="report-dialog">
      <DialogTitle>Report a wrong image</DialogTitle>
      <DialogContent><TextField fullWidth multiline minRows={2} size="small" sx={{ mt: 1 }} label="What is wrong? (required)" value={reason} onChange={(e) => setReason(e.target.value)} inputProps={{ 'data-testid': 'report-reason' }} />
        {send.isError && <Alert severity="warning" sx={{ mt: 1 }}>{sourcingError(send.error)}</Alert>}</DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" color="warning" disabled={reason.trim().length < 3 || send.isPending} onClick={() => send.mutate()} data-testid="report-send">Hide and report</Button></DialogActions>
    </Dialog>
  );
};

/** Every image shows where it came from, how sure we are it is this exact product, and whether the address still works. Nothing is copied or redistributed. */
const ImageGallery = ({ oppId, name, images, canEdit }: { oppId: string; name: string; images: Img[]; canEdit: boolean }) => {
  const qc = useQueryClient();
  const [adding, setAdding] = useState(false);
  const [reporting, setReporting] = useState<Img | null>(null);
  const [active, setActive] = useState<string | null>(null);
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['src'] }); };
  const primary = useMutation({ mutationFn: (id: string) => sourcingApi.setPrimaryImage(oppId, id), onSuccess: refresh });
  const recheck = useMutation({ mutationFn: (id: string) => sourcingApi.recheckImage(id), onSuccess: refresh });
  const shown = images.filter((i) => !i.reported);
  const main = shown.find((i) => i.id === active) ?? shown.find((i) => i.is_primary) ?? shown.find((i) => i.status === 'VALID') ?? shown[0];
  return (
    <Box data-testid="image-gallery">
      <ProductImage url={main && main.status === 'VALID' ? main.url : null} name={name} size={260} level={main?.match_level} />
      {main && (
        <Stack spacing={0.25} sx={{ mt: 0.75 }} data-testid="image-caption">
          <Stack direction="row" spacing={0.75} alignItems="center" flexWrap="wrap" useFlexGap><MatchChip level={main.match_level} />
            <Chip size="small" variant="outlined" color={main.status === 'VALID' ? 'success' : 'error'} label={pretty(main.status)} /></Stack>
          {main.status !== 'VALID' && main.status_detail && <Typography variant="caption" color="error">{main.status_detail}</Typography>}
          {main.match_level !== 'EXACT_MODEL' && <Typography variant="caption" color="warning.main">Reference image: it may not show this exact product.</Typography>}
          <Typography variant="caption" color="text.secondary">{main.attribution ?? 'Source not stated'}{main.source_page_url && <> · <Link href={safeHref(main.source_page_url)} target="_blank" rel="noopener noreferrer">source page</Link></>}</Typography>
          {main.rights_note && <Typography variant="caption" color="text.secondary">{main.rights_note}</Typography>}
        </Stack>
      )}
      {shown.length > 1 && (
        <Stack direction="row" spacing={0.75} sx={{ mt: 1, overflowX: 'auto' }} data-testid="image-thumbs">
          {shown.map((i) => (
            <Box key={i.id} onClick={() => setActive(i.id)} sx={{ width: 64, flex: '0 0 auto', cursor: 'pointer', border: 2, borderColor: i.id === main?.id ? 'primary.main' : 'transparent', borderRadius: 1 }} data-testid="image-thumb">
              <ProductImage url={i.status === 'VALID' ? i.url : null} name={name} size={56} level={i.match_level} />
            </Box>
          ))}
        </Stack>
      )}
      {shown.length === 0 && <Typography variant="body2" color="text.secondary" sx={{ mt: 0.75 }} data-testid="no-images">No verified image is recorded. A placeholder is shown instead; nothing is guessed.</Typography>}
      {images.some((i) => i.reported) && <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 0.5 }}>{images.filter((i) => i.reported).length} image(s) reported as wrong and hidden.</Typography>}
      {canEdit && (
        <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap sx={{ mt: 1 }}>
          <Button size="small" variant="outlined" onClick={() => setAdding(true)} data-testid="image-add">Add / correct image</Button>
          {main && !main.is_primary && main.status === 'VALID' && <Button size="small" onClick={() => primary.mutate(main.id)} disabled={primary.isPending} data-testid="image-primary">Use as main image</Button>}
          {main && <Button size="small" onClick={() => recheck.mutate(main.id)} disabled={recheck.isPending} data-testid="image-recheck">Re-check link</Button>}
          {main && <Button size="small" color="warning" onClick={() => setReporting(main)} data-testid="image-report">Report mismatch</Button>}
        </Stack>
      )}
      {(primary.isError || recheck.isError) && <Alert severity="warning" sx={{ mt: 1 }}>{sourcingError(primary.error ?? recheck.error)}</Alert>}
      {adding && <AddImage oppId={oppId} onClose={() => setAdding(false)} onSaved={refresh} />}
      {reporting && <Report image={reporting} onClose={() => setReporting(null)} onDone={refresh} />}
    </Box>
  );
};

export default ImageGallery;
