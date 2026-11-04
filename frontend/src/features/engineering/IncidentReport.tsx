import { Alert, Box, Chip, Divider, Paper, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import type { EngIncidentReport } from '@/api/engineeringAi';

const List = ({ items, mono }: { items: string[]; mono?: boolean }) => (
  <Box component="ul" sx={{ m: 0, pl: 2.5 }}>{items.map((x) => <Typography key={x} component="li" variant="body2" sx={mono ? { fontFamily: 'monospace', fontSize: 12 } : undefined}>{x}</Typography>)}</Box>
);
const Section = ({ title, tag, children, id }: { title: string; tag?: { label: string; color?: 'default' | 'warning' | 'info' | 'success' }; children: React.ReactNode; id?: string }) => (
  <Paper variant="outlined" sx={{ p: 1.5 }} data-testid={id}>
    <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 0.75 }}>
      <Typography variant="subtitle2" fontWeight={800}>{title}</Typography>
      {tag && <Chip size="small" label={tag.label} color={tag.color ?? 'default'} />}
    </Stack>
    {children}
  </Paper>
);
const Unavailable = ({ reason }: { reason: string }) => <Alert severity="info" icon={false} sx={{ py: 0 }}>Not available: {reason}</Alert>;

/** The ten parts of the investigation. FACTS (from logs, git and files) and HYPOTHESES (explanations, never confirmed) are labelled separately throughout. */
const IncidentReport = ({ report, only }: { report: EngIncidentReport; only?: 'resolution' }) => {
  const proposed = (
    <Section title="Proposed resolution" tag={{ label: 'suggestion, not applied', color: 'warning' }} id="eng-resolution">
      <Typography variant="caption" color="text.secondary">Nothing has been changed. These are suggestions for a person to review, test and apply.</Typography>
      <Typography variant="body2" fontWeight={700} sx={{ mt: 0.75 }}>Suggested remediation</Typography><List items={report.remediation} />
      <Typography variant="body2" fontWeight={700} sx={{ mt: 0.75 }}>Reproduction steps</Typography><List items={report.reproduction} />
      <Typography variant="body2" fontWeight={700} sx={{ mt: 0.75 }}>Validation plan</Typography><List items={report.validation_plan} />
    </Section>
  );
  if (only === 'resolution') return <Stack spacing={1.5}>{proposed}</Stack>;
  const g = report.git;
  return (
    <Stack spacing={1.5} data-testid="eng-incident-report">
      <Section title="1. Incident summary" tag={{ label: 'fact', color: 'success' }}>
        <Typography variant="body2">{report.summary}</Typography>
        <Typography variant="caption" color="text.secondary">{report.reference} is a derived fingerprint, not an incident ticket.{report.origin !== 'real' && ' Includes SYNTHETIC test data.'}</Typography>
      </Section>
      <Section title="2. Observed evidence" tag={{ label: 'facts from log records', color: 'success' }}>
        <Box sx={{ overflowX: 'auto' }}>
          <Table size="small"><TableHead><TableRow><TableCell>Id</TableCell><TableCell>Time (UTC)</TableCell><TableCell>Service</TableCell><TableCell>Message</TableCell></TableRow></TableHead>
            <TableBody>{report.observed_evidence.map((e) => (
              <TableRow key={e.id}><TableCell>L{e.id}</TableCell><TableCell sx={{ whiteSpace: 'nowrap' }}>{e.ts.replace('T', ' ').slice(0, 19)}</TableCell>
                <TableCell>{e.service} {e.origin === 'synthetic' && <Chip size="small" color="warning" label="synthetic" />}</TableCell>
                <TableCell sx={{ maxWidth: 520, wordBreak: 'break-word' }}>{e.message}{e.exception_head ? ` | ${e.exception_head}` : ''}</TableCell></TableRow>))}</TableBody></Table>
        </Box>
      </Section>
      <Section title="3. Logs before and after" tag={{ label: 'facts', color: 'success' }}>
        <Typography variant="body2">ERROR records in the 2 hours before this signature first appeared: <b>{report.before_after.errors_2h_before_first_seen}</b>; in the 2 hours after: <b>{report.before_after.errors_2h_after_first_seen}</b>.</Typography>
        {report.before_after.other_error_groups_within_30_min.length > 0 && <List items={report.before_after.other_error_groups_within_30_min.map((n) => `${n.count} x ${n.text} (${n.category.toLowerCase()}, ${n.services.join(', ')})`)} />}
      </Section>
      <Section title="4. Cross-service pattern" tag={{ label: 'facts', color: 'success' }}>
        {report.cross_service.traced_requests.length === 0 ? <Unavailable reason="no failing request with a correlation id could be traced across services." /> : (
          <>
            <Typography variant="body2">{report.cross_service.traced_requests.length} failing request(s) traced. First failing service: {Object.entries(report.cross_service.first_failing_service_counts).map(([s, n]) => `${s} (${n})`).join(', ') || 'unknown'}.</Typography>
            <List mono items={report.cross_service.traced_requests.map((c) => `${c.correlation_id.slice(0, 12)}… crossed ${c.services.join(' → ')} (${c.records} records)`)} />
          </>
        )}
      </Section>
      <Section title="5. Database and dependency errors" tag={{ label: 'facts', color: 'success' }}>
        {report.dependency_errors.co_occurring_groups.length === 0 && report.dependency_errors.postgres_error_records_nearby === 0
          ? <Typography variant="body2">No co-occurring database, timeout or downstream error group, and no PostgreSQL ERROR records, were found around this time.</Typography>
          : <List items={[...report.dependency_errors.co_occurring_groups.map((d) => `${d.count} x ${d.text} (${d.category.toLowerCase()})`), ...(report.dependency_errors.postgres_error_records_nearby ? [`${report.dependency_errors.postgres_error_records_nearby} PostgreSQL ERROR record(s) around this time`] : [])]} />}
      </Section>
      <Section title="6. Relevant files, commits and tests" tag={{ label: 'candidates, not proof', color: 'warning' }}>
        {report.code.files.length === 0 ? <Unavailable reason="no project stack frame could be mapped to a source file." /> : report.code.files.map((f) => (
          <Box key={f.path} sx={{ mb: 1 }}>
            <Typography variant="body2" fontWeight={700} sx={{ fontFamily: 'monospace' }}>{f.path}{f.line ? `:${f.line}` : ''}{f.symbol ? ` (${f.symbol})` : ''}</Typography>
            {f.excerpt.length > 0 && <Box component="pre" sx={{ m: 0, p: 1, bgcolor: 'action.hover', borderRadius: 1, fontSize: 11, overflowX: 'auto' }}>{f.excerpt.join('\n')}</Box>}
            <Typography variant="caption" color="text.secondary">{f.tests.length ? `Tests: ${f.tests.join(', ')}` : 'No related test file was found.'}</Typography>
          </Box>
        ))}
        <Divider sx={{ my: 1 }} />
        {!g.available ? <Unavailable reason="git is not available here, so commit history could not be read." /> : (
          <>
            {g.head && <Typography variant="caption" display="block">Repository HEAD: {g.head.commit} ({g.head.date.slice(0, 10)}) {g.head.subject}</Typography>}
            {(g.commits_touching_suspected_files ?? []).length > 0 && <List items={(g.commits_touching_suspected_files ?? []).map((c) => `${c.commit} ${c.date.slice(0, 10)} ${c.subject}`)} mono />}
            {g.commits_touching_suspected_files && g.commits_touching_suspected_files.length === 0 && <Typography variant="body2">No commit touched the suspected files in the week before this signature first appeared.</Typography>}
            {g.uncommitted_suspected_files.length > 0 && <Alert severity="warning" sx={{ mt: 0.5 }}>Uncommitted changes in: {g.uncommitted_suspected_files.join(', ')}. The running build may differ from the last commit.</Alert>}
            <Typography variant="caption" color="text.secondary" display="block">{g.note}</Typography>
          </>
        )}
      </Section>
      <Section title="7. Deployment and configuration" tag={{ label: 'timing only', color: 'warning' }}>
        {report.deployment.available
          ? <Typography variant="body2">{report.deployment.service} {report.deployment.version} was recorded as deployed {report.deployment.minutes_before_first_seen} minutes before this signature first appeared. {report.deployment.note}</Typography>
          : <Unavailable reason={report.deployment.reason ?? 'no deployment record.'} />}
        <Box sx={{ mt: 0.75 }}>{report.configuration_changes.available ? null : <Unavailable reason={report.configuration_changes.reason} />}</Box>
      </Section>
      {report.related_incidents.length > 0 && (
        <Section title="8. Related incident groups" tag={{ label: 'fact', color: 'success' }}>
          <List items={report.related_incidents.map((r) => `${r.reference} [${r.level}] ${r.text} (${r.shared_requests} shared request(s))`)} />
        </Section>
      )}
      <Section title="9. Likely cause: ranked hypotheses" tag={{ label: 'NOT confirmed', color: 'warning' }} id="eng-report-hypotheses">
        <Typography variant="caption" color="text.secondary">{report.likely_cause_status}. Overall confidence: {report.confidence}.</Typography>
        {report.hypotheses.map((h, i) => (
          <Box key={h.statement} sx={{ mt: 1 }}>
            <Typography variant="body2"><b>{i + 1}.</b> {h.statement}</Typography>
            <Typography variant="caption" color="text.secondary">Confidence {h.confidence} · basis: {h.basis} · to confirm: {h.to_confirm}{h.evidence_ids.length ? ` · evidence ${h.evidence_ids.map((x) => `L${x}`).join(', ')}` : ''}</Typography>
          </Box>
        ))}
      </Section>
      {proposed}
      <Section title="10. Additional evidence needed and limitations">
        <List items={report.additional_evidence_needed} />
        <Divider sx={{ my: 0.75 }} />
        <List items={report.limitations} />
      </Section>
    </Stack>
  );
};

export default IncidentReport;
