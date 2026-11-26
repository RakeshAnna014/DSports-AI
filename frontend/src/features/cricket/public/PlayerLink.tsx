import { Link } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';

/** A player's name as a link to the public career page. The id only opens that player's statistics (names and numbers). A guest, or a player who hid the page, has no id: the name is plain text. */
export const PlayerLink = ({ id, name }: { id: string | null; name: string }) => (
  id === null ? <span data-testid="player-name-plain">{name}</span> : <Link component={RouterLink} to={`/p/${encodeURIComponent(id)}`} color="inherit" underline="hover" data-testid="player-link">{name}</Link>
);
