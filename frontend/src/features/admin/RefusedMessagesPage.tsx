import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Box, Button, MenuItem, Paper, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material';
import { GppBad } from '@mui/icons-material';
import { useNavigate } from 'react-router-dom';
import { isAdmin } from '@/api/venues';
import { getApiErrorMessage } from '@/api/errors';
import { topicRefusalsApi, type RefusalFilter, type RefusedMessage } from '@/api/topicRefusals';
import { useAuthStore } from '@/store/authStore';
import { EmptyState, ErrorState } from '@/components/common/States';

const TOPIC_LABEL: Record<RefusedMessage['topic'], string> = { OFF_TOPIC: 'Off topic', BUSINESS: 'Business (not allowed for this role)' };
const ROLE_LABEL: Record<RefusedMessage['role'], string> = { GUEST: 'Guest', CUSTOMER: 'Customer', ADMIN: 'Admin' };

/** What the assistant refused to answer: topic, role of the asker, the (redacted) text and the time. It never shows who asked. The server enforces the ADMIN role. */
const RefusedMessagesPage = () => {
  const navigate = useNavigate();
  const { user, isAuthenticated } = useAuthStore();
  const admin = isAuthenticated && isAdmin(user?.roles);
  const [topic, setTopic] = useState<RefusedMessage['topic'] | ''>('');
  const [role, setRole] = useState<RefusedMessage['role'] | ''>('');
  const [error, setError] = useState<string | null>(null);
  const filter: RefusalFilter = { topic: topic || undefined, role: role || undefined };
  const rows = useQuery({ queryKey: ['topic-refusals', topic, role], queryFn: () => topicRefusalsApi.list(filter), enabled: admin });

  const download = async () => {
    try {
      const url = URL.createObjectURL(await topicRefusalsApi.csv(filter));
      const link = document.createElement('a');
      link.href = url;
      link.download = 'refused-messages.csv';
      link.click();
      URL.revokeObjectURL(url);
      setError(null);
    } catch (err) {
      setError(getApiErrorMessage(err, 'The file could not be downloaded.'));
    }
  };

  if (!admin) {
    return <EmptyState icon={<GppBad sx={{ fontSize: 56 }} />} title="Administrators only" body="This area is for administrators." action={<Button variant="contained" onClick={() => navigate('/')}>Home</Button>} />;
  }
  return (
    <Box>
      <Typography variant="h3" component="h1">Refused messages</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Messages the assistant would not answer. Each row has the topic, the role of the person who asked, the text (with e-mail addresses, numbers and tokens removed, cut at 300 characters) and the time. Who asked is not stored.
      </Typography>
      {error && <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>{error}</Alert>}
      <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} alignItems={{ sm: 'center' }}>
          <TextField size="small" select label="Topic" value={topic} onChange={(e) => setTopic(e.target.value as RefusedMessage['topic'] | '')} sx={{ minWidth: 220 }}>
            <MenuItem value="">All</MenuItem>
            {(Object.keys(TOPIC_LABEL) as RefusedMessage['topic'][]).map((t) => <MenuItem key={t} value={t}>{TOPIC_LABEL[t]}</MenuItem>)}
          </TextField>
          <TextField size="small" select label="Role" value={role} onChange={(e) => setRole(e.target.value as RefusedMessage['role'] | '')} sx={{ minWidth: 160 }}>
            <MenuItem value="">All</MenuItem>
            {(Object.keys(ROLE_LABEL) as RefusedMessage['role'][]).map((r) => <MenuItem key={r} value={r}>{ROLE_LABEL[r]}</MenuItem>)}
          </TextField>
          <Box sx={{ flexGrow: 1 }} />
          <Button variant="outlined" onClick={() => void download()} disabled={!rows.data?.length} data-testid="download-refusals">Download CSV</Button>
        </Stack>
      </Paper>
      {rows.isError && <ErrorState body="We couldn't load the refused messages." onRetry={() => void rows.refetch()} />}
      {rows.data && rows.data.length === 0 && <Typography variant="body2" color="text.secondary" data-testid="no-refusals">Nothing has been refused yet.</Typography>}
      {rows.data && rows.data.length > 0 && (
        <Table size="small" data-testid="refusals-table">
          <TableHead><TableRow><TableCell>Time</TableCell><TableCell>Topic</TableCell><TableCell>Role</TableCell><TableCell>Message</TableCell></TableRow></TableHead>
          <TableBody>
            {rows.data.map((r, i) => (
              <TableRow key={`${r.time}-${i}`} data-testid="refusal-row">
                <TableCell sx={{ whiteSpace: 'nowrap' }}>{new Date(r.time).toLocaleString()}</TableCell>
                <TableCell>{TOPIC_LABEL[r.topic]}</TableCell>
                <TableCell>{ROLE_LABEL[r.role]}</TableCell>
                <TableCell sx={{ wordBreak: 'break-word' }}>{r.text}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </Box>
  );
};

export default RefusedMessagesPage;
