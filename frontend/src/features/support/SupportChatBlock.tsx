import { Box, Stack, Typography } from '@mui/material';
import type { AiSupport } from '@/types/support';
import { fromChat } from '@/types/support';
import { SupportCaseCard, SupportCaseList } from './parts';
import { InvestigationEvidence } from './InvestigationEvidence';
import { ResolutionSummary } from './ResolutionSummary';

/** The structured support part of an assistant response. The case number, status, priority and escalation shown here are exactly what the backend returned. */
export const SupportChatBlock = ({ support }: { support: AiSupport }) => {
  if (support.kind === 'INVESTIGATION') {
    return (
      <Stack spacing={1} data-testid="support-investigation">
        <InvestigationEvidence s={support} />
        <ResolutionSummary s={support} />
      </Stack>
    );
  }
  if (support.kind === 'CASE' || support.kind === 'CASE_CREATED') {
    return <SupportCaseCard c={fromChat(support.case)} created={support.kind === 'CASE_CREATED'} />;
  }
  if (support.kind === 'CASE_LIST') {
    return <SupportCaseList cases={support.cases.map((c) => fromChat({ ...c, case_id: c.case_id }))} />;
  }
  if (support.kind === 'ASK_INFO' && support.ask) {
    return <Box data-testid="support-ask"><Typography variant="body2" fontWeight={700}>{support.ask}</Typography></Box>;
  }
  return null;
};
