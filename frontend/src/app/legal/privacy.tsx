import { LegalDocumentPage } from './terms';

// /legal/privacy: the current privacy policy, loaded from the server.
export default function PrivacyPage() {
  return <LegalDocumentPage docType="PRIVACY" />;
}
