export default function KycBadge({ status }) {
  const cls = ['APPROVED', 'PENDING', 'REJECTED'].includes(status)
    ? `kyc-badge kyc-${status}`
    : 'kyc-badge kyc-unknown';
  return <span className={cls}>{status ?? 'unknown'}</span>;
}
