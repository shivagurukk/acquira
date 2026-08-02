/**
 * Acquira UI kit — token-driven primitives shared across admin surfaces.
 *
 * Import from the barrel:
 *   import { Page, Card, Button, DataTable, Modal, FormField, Input } from '../../components/ui';
 *
 * These replace hand-rolled inline-styled buttons, tables, modals and tabs.
 * Anything you'd be tempted to write with `style={{}}` on a page probably
 * belongs here instead.
 */
import './ui.css';

export { default as Page, Stack, Row } from './Page';
export { default as Card, CardHeader, CardBody, CardFooter } from './Card';
export { default as Button } from './Button';
export { default as Badge, StatusBadge } from './Badge';
export { default as Alert } from './Alert';
export { default as Tabs } from './Tabs';
export { default as DataTable } from './DataTable';
export { default as Modal } from './Modal';
export { ConfirmProvider } from './ConfirmDialog';
export { useConfirm } from './confirmContext';
export {
  FormField,
  FormGrid,
  Input,
  Textarea,
  Select,
  Checkbox,
  Switch,
} from './Form';
