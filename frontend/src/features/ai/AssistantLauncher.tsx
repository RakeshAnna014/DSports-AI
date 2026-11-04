import AssistantPanel from '@/features/ai/AssistantPanel';
import ConfirmActionDialog from '@/features/ai/ConfirmActionDialog';

/**
 * The secondary, global assistant: a side drawer opened from the small AI icon in the header, plus the confirmation dialog for
 * actions that change data. The primary AI experience is the search box on the Home and Products pages; both use the SAME
 * conversation (see aiStore), so the drawer shows exactly what the search box asked and answered.
 */
const AssistantLauncher = () => (
  <>
    <AssistantPanel />
    <ConfirmActionDialog />
  </>
);

export default AssistantLauncher;
