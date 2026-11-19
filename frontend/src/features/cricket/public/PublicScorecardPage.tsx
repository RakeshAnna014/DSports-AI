import { Alert, Box, Button, Chip, Paper, Skeleton, Stack, Typography } from '@mui/material';
import { Link as RouterLink, useParams } from 'react-router-dom';
import { usePublicScorecard } from './usePublicScorecard';
import { ShareScorecardButton } from './ShareScorecardButton';
import { Headline, InningsBlock, STATUS_LABEL } from './ScorecardView';

/**
 * The public scorecard of a match, readable without signing in and made for a phone: the score, overs, run rate and the chase, then each innings with its batters
 * (and how they were out), bowlers, extras, fall of wickets and any bowling waivers. It refreshes itself every few seconds while the tab is visible, using the
 * server's ETag so an unchanged match costs almost nothing, and stops when the match is over.
 */
const PublicScorecardPage = () => {
  const { id = '' } = useParams();
  const feed = usePublicScorecard(id);
  const { card } = feed;

  if (!card) {
    return (
      <Box sx={{ maxWidth: 640, mx: 'auto' }} data-testid="public-scorecard">
        {feed.loading && <Skeleton variant="rounded" height={160} />}
        {feed.notFound && (
          <Alert severity="info" data-testid="public-not-found" action={<Button color="inherit" size="small" onClick={feed.refresh}>Check again</Button>}>
            This match has not started yet, or the link is not right. This page checks again every few seconds, so it will show the score as soon as the first ball is bowled.
          </Alert>
        )}
        {feed.failing && !feed.notFound && (
          <Alert severity="warning" data-testid="public-stale" action={<Button color="inherit" size="small" onClick={feed.refresh}>Try again</Button>}>
            The score could not be loaded. Retrying.
          </Alert>
        )}
        <Button component={RouterLink} to="/live" size="small" sx={{ mt: 2 }}>Live matches</Button>
      </Box>
    );
  }

  const current = card.innings[card.innings.length - 1];
  const earlier = card.innings.slice(0, -1);
  const title = `${card.teamA} vs ${card.teamB}`;
  const where = [card.venue, card.city].filter(Boolean).join(', ');

  return (
    <Box sx={{ maxWidth: 640, mx: 'auto' }} data-testid="public-scorecard">
      <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap sx={{ mb: 1 }}>
        <Typography variant="h5" component="h1">{title}</Typography>
        <Chip size="small" color={card.live ? 'error' : 'default'} label={STATUS_LABEL[card.status] ?? card.status} data-testid="public-status" />
      </Stack>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {card.overs} overs{where ? ` · ${where}` : ''}
        {card.scheduledAt ? ` · ${new Date(card.scheduledAt).toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' })}` : ''}
      </Typography>

      {feed.failing && (
        <Alert severity="warning" sx={{ mb: 1 }} data-testid="public-stale" action={<Button color="inherit" size="small" onClick={feed.refresh}>Try now</Button>}>
          Can&apos;t refresh right now. This is the last score that arrived. Retrying.
        </Alert>
      )}

      {card.result && card.result.text && (
        <Paper variant="outlined" sx={{ p: 2, mb: 1.5, borderColor: 'success.main' }} data-testid="public-result">
          <Typography variant="h6">{card.result.text}</Typography>
        </Paper>
      )}

      {current ? (
        <Stack spacing={1.5}>
          <Headline card={card} innings={current} />
          {earlier.map((i) => (
            <Typography key={i.number} variant="body1" data-testid="public-earlier">{i.battingTeam} {i.runs}/{i.wickets} ({i.overs} ov)</Typography>
          ))}
          {card.status === 'INNINGS_BREAK' && <Alert severity="info" data-testid="public-break">Innings break. The chase starts soon.</Alert>}
          {card.innings.map((i, index) => <InningsBlock key={i.number} innings={i} open={index === card.innings.length - 1} />)}
        </Stack>
      ) : null}

      <Stack spacing={1.5} sx={{ mt: 2 }}>
        <ShareScorecardButton matchId={card.matchId} title={title} />
        <Typography variant="caption" color="text.secondary" data-testid="public-updated">
          {feed.finished
            ? 'Final scorecard. '
            : 'Updates by itself while this page is open. '}
          {feed.checkedAt ? `Last checked ${new Date(feed.checkedAt).toLocaleTimeString('en-GB')}.` : ''}
          {feed.finished && <Button size="small" onClick={feed.refresh} data-testid="public-refresh">Check again</Button>}
        </Typography>
        <Button component={RouterLink} to="/live" size="small" sx={{ alignSelf: 'flex-start' }}>Live matches</Button>
      </Stack>
    </Box>
  );
};

export default PublicScorecardPage;
