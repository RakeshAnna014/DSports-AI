import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, List, ListItemButton, ListItemText } from '@mui/material';

export interface Choice {
  id: string;
  label: string;
  detail?: string | null;
  disabled?: boolean;
}

/**
 * Pick one of a list. With {@code blocking} the sheet cannot be dismissed: scoring cannot go on until the batter or bowler is chosen. A choice that is not
 * allowed stays in the list, greyed, with the reason, so the scorer sees why.
 */
export const ChooserDialog = ({ title, notice, choices, blocking, busy, onChoose, onClose }: {
  title: string; notice?: string | null; choices: Choice[]; blocking?: boolean; busy?: boolean; onChoose: (id: string) => void; onClose?: () => void;
}) => (
  <Dialog open fullWidth maxWidth="xs" onClose={blocking ? undefined : onClose} data-testid="chooser">
    <DialogTitle>{title}</DialogTitle>
    <DialogContent dividers>
      {notice && <Alert severity="warning" sx={{ mb: 1 }} data-testid="chooser-notice">{notice}</Alert>}
      <List disablePadding>
        {choices.map((c) => (
          <ListItemButton key={c.id} disabled={c.disabled || busy} onClick={() => onChoose(c.id)} data-testid="choice">
            <ListItemText primary={c.label} secondary={c.detail} />
          </ListItemButton>
        ))}
        {choices.length === 0 && <ListItemText primary="Nobody to choose from." />}
      </List>
    </DialogContent>
    {!blocking && onClose && <DialogActions><Button onClick={onClose}>Cancel</Button></DialogActions>}
  </Dialog>
);
