import { useState } from 'react';
import { Platform, StyleSheet, Text, View } from 'react-native';
import { Banner, Button, colors, fontSize, radius, space } from '@/components/web/ui';
import { canSaveFiles, copyText, saveTextFile } from '@/lib/account/browser';
import { RECOVERY_CODES_FILE_NAME, recoveryCodesFileText } from '@/lib/account/format';
import { ButtonRow, Para, SubHeading } from './account-ui';

/**
 * Recovery codes straight after they were generated. The server keeps only hashes, so this is the one time
 * they can be read: the panel stays until the person confirms that the codes are saved.
 */
export function RecoveryCodesPanel({ codes, onDone }: {
  codes:  readonly string[];
  /** Called when the person confirms the codes are saved; the caller then drops them from memory. */
  onDone: () => void;
}) {
  const [feedback, setFeedback] = useState<{ tone: 'success' | 'danger'; text: string } | null>(null);

  const onCopy = async () => {
    const ok = await copyText(codes.join('\n'));
    setFeedback(ok
      ? { tone: 'success', text: 'Recovery codes copied. Paste them somewhere safe, such as a password manager.' }
      : { tone: 'danger', text: 'The codes could not be copied. Select them and copy them by hand, or download them.' });
  };

  const onDownload = () => {
    try {
      saveTextFile(recoveryCodesFileText(codes), RECOVERY_CODES_FILE_NAME);
      setFeedback({ tone: 'success', text: `Recovery codes saved as ${RECOVERY_CODES_FILE_NAME}. Keep the file private.` });
    } catch {
      setFeedback({ tone: 'danger', text: 'The file could not be saved. Copy the codes instead.' });
    }
  };

  return (
    <View style={styles.panel}>
      <SubHeading>Your recovery codes</SubHeading>
      <Banner
        tone="warning"
        text="Save these codes now. They are shown only once and cannot be shown again."
      />
      <Para muted>
        Each code works once. Use one to sign in if you lose your phone or cannot open your authenticator app.
      </Para>
      <View style={styles.codes} accessibilityRole="list" aria-label="Recovery codes">
        {codes.map(code => (
          <View key={code} style={styles.codeCell} role="listitem">
            <Text style={styles.code} selectable>{code}</Text>
          </View>
        ))}
      </View>
      {feedback ? <Banner tone={feedback.tone} text={feedback.text} onDismiss={() => setFeedback(null)} /> : null}
      <ButtonRow>
        <Button label="Copy codes" icon="copy-outline" variant="secondary" onPress={onCopy} />
        {canSaveFiles() ? (
          <Button label="Download codes" icon="download-outline" variant="secondary" onPress={onDownload} />
        ) : null}
        <Button label="I have saved these codes" icon="checkmark-outline" onPress={onDone} />
      </ButtonRow>
    </View>
  );
}

const styles = StyleSheet.create({
  panel: { gap: space.md, minWidth: 0 },
  codes: {
    flexDirection: 'row', flexWrap: 'wrap', gap: space.sm, padding: space.md,
    backgroundColor: colors.bgSubtle, borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, maxWidth: 420,
  },
  codeCell: { flexGrow: 1, flexBasis: 150, minWidth: 0 },
  code: {
    fontFamily: Platform.select({ web: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace', ios: 'Menlo', default: 'monospace' }),
    fontSize: fontSize.lg, fontWeight: '600', color: colors.text, letterSpacing: 1,
  },
});
