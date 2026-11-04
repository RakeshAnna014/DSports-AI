import { Box, Chip, Stack, Tooltip, Typography } from '@mui/material';
import { MenuBookOutlined } from '@mui/icons-material';
import type { AiSource } from '@/types/ai';

const SourcesList = ({ sources }: { sources: AiSource[] }) => (
  <Box data-testid="ai-sources" sx={{ mt: 1 }}>
    <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.5 }}>Sources</Typography>
    <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 0.5 }}>
      {sources.map((s) => (
        <Tooltip key={s.chunkId} title={<Box><strong>{s.title ?? s.source}</strong>{s.snippet ? <Box sx={{ mt: 0.5 }}>{s.snippet}</Box> : null}</Box>}>
          <Chip size="small" variant="outlined" icon={<MenuBookOutlined />}
            label={`${s.title ?? s.source}${s.page ? ` · p.${s.page}` : ''}${s.documentType ? ` · ${s.documentType}` : ''}`} />
        </Tooltip>
      ))}
    </Stack>
  </Box>
);

export default SourcesList;
