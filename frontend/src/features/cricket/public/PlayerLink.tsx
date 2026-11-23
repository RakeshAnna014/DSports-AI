import { Link } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';

/** A player's name as a link to the public career page. The id only opens that player's statistics (names and numbers). */
export const PlayerLink = ({ id, name }: { id: string; name: string }) => (
  <Link component={RouterLink} to={`/p/${encodeURIComponent(id)}`} color="inherit" underline="hover" data-testid="player-link">{name}</Link>
);
