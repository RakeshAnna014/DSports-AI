import { Component, type ReactNode } from 'react';
import { Alert } from '@mui/material';

/** One answer that cannot be drawn must never take the whole page down: show a contained message and keep the rest of the area (and the storefront) usable. */
export class ContainBoundary extends Component<{ children: ReactNode; label?: string }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidCatch(error: unknown) {
    if (import.meta.env.DEV) console.warn('[inventory] a component failed to render', error);
  }

  render() {
    if (this.state.failed) {
      return <Alert severity="warning" data-testid="render-failed" sx={{ my: 1 }}>{this.props.label ?? 'This part could not be displayed.'} The data is unaffected; reload to try again.</Alert>;
    }
    return this.props.children;
  }
}
