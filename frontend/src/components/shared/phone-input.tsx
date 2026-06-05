import { Ionicons } from '@expo/vector-icons';
import { useEffect, useMemo, useRef, useState } from 'react';
import {
  FlatList,
  Modal,
  Platform,
  Pressable,
  SafeAreaView,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import { type Country, countries, defaultCountry } from '@/lib/phone/countries';

const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const TEXT   = '#111827';
const RED    = '#EF4444';
const WHITE  = '#FFFFFF';
const OVERLAY= 'rgba(0,0,0,0.45)';

interface PhoneInputProps {
  value: string;
  onChange: (value: string) => void;
  error?: string;
  accentColor?: string;
  /** Pass s() from mobile components for proportional sizing. Web uses identity. */
  scale?: (n: number) => number;
}

function buildValue(dial: string, local: string) {
  return local.trim() ? `${dial} ${local.trim()}` : dial;
}

function parseValue(value: string): { country: Country; local: string } {
  for (const c of [...countries].sort((a, b) => b.dial.length - a.dial.length)) {
    if (value.startsWith(c.dial)) {
      return { country: c, local: value.slice(c.dial.length).trim() };
    }
  }
  return { country: defaultCountry, local: value };
}

export function PhoneInput({ value, onChange, error, accentColor = '#0D9488', scale }: PhoneInputProps) {
  const s = scale ?? ((n: number) => n);
  const isWeb = Platform.OS === 'web';

  const parsed         = useMemo(() => parseValue(value || ''), []);
  const [selected, setSelected]       = useState<Country>(parsed.country);
  const [localNumber, setLocalNumber] = useState(parsed.local);
  const [modalOpen, setModalOpen]     = useState(false);
  const [search, setSearch]           = useState('');
  const searchRef = useRef<TextInput>(null);

  // Keep parent form in sync whenever selected or localNumber changes
  useEffect(() => {
    onChange(buildValue(selected.dial, localNumber));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selected, localNumber]);

  function handleSelect(country: Country) {
    setSelected(country);
    setModalOpen(false);
    setSearch('');
  }

  function handleLocalChange(text: string) {
    // Allow digits, spaces, dashes, parentheses
    setLocalNumber(text.replace(/[^\d\s\-()+]/g, ''));
  }

  const filtered = useMemo(() => {
    const q = search.toLowerCase().trim();
    if (!q) return countries;
    return countries.filter(
      c => c.name.toLowerCase().includes(q) || c.dial.includes(q) || c.code.toLowerCase().includes(q)
    );
  }, [search]);

  const fontSize   = s(isWeb ? 14 : 13);
  const iconSize   = s(isWeb ? 16 : 14);
  const padV       = s(isWeb ? 14 : 12);
  const padH       = s(isWeb ? 14 : 12);
  const radius     = s(isWeb ? 12 : 10);
  const flagSize   = s(isWeb ? 18 : 16);
  const dialSize   = s(isWeb ? 13 : 12);

  return (
    <View>
      {/* ── Input row ── */}
      <View style={[
        styles.inputRow,
        { borderRadius: radius, paddingVertical: padV, borderColor: error ? RED : BORDER },
      ]}>
        {/* Country selector button */}
        <TouchableOpacity
          style={[styles.selector, { paddingHorizontal: padH, gap: s(6) }]}
          onPress={() => setModalOpen(true)}
          activeOpacity={0.7}
        >
          <Text style={{ fontSize: flagSize }}>{selected.flag}</Text>
          <Text style={[styles.dialCode, { fontSize: dialSize }]}>{selected.dial}</Text>
          <Ionicons name="chevron-down-outline" size={iconSize} color={GRAY} />
        </TouchableOpacity>

        {/* Divider */}
        <View style={styles.divider} />

        {/* Number input */}
        <TextInput
          style={[styles.input, { fontSize, paddingHorizontal: s(10) }]}
          placeholder="Phone number"
          placeholderTextColor={GRAY}
          value={localNumber}
          onChangeText={handleLocalChange}
          keyboardType="phone-pad"
          autoCorrect={false}
        />
      </View>

      {/* Field error */}
      {!!error && (
        <View style={[styles.fieldError, { gap: s(5), marginTop: s(4) }]}>
          <Ionicons name="alert-circle-outline" size={s(13)} color={RED} />
          <Text style={[styles.fieldErrorText, { fontSize: s(isWeb ? 12 : 11) }]}>{error}</Text>
        </View>
      )}

      {/* ── Country picker modal ── */}
      <Modal
        visible={modalOpen}
        animationType="slide"
        transparent
        onRequestClose={() => setModalOpen(false)}
        onShow={() => setTimeout(() => searchRef.current?.focus(), 100)}
      >
        <Pressable style={styles.overlay} onPress={() => setModalOpen(false)}>
          <Pressable
            style={[
              styles.sheet,
              isWeb && styles.sheetWeb,
            ]}
            onPress={() => {}} // prevent close when tapping inside
          >
            <SafeAreaView style={{ flex: 1 }}>
              {/* Header */}
              <View style={styles.sheetHeader}>
                <Text style={styles.sheetTitle}>Select country</Text>
                <TouchableOpacity onPress={() => setModalOpen(false)} activeOpacity={0.7} style={styles.closeBtn}>
                  <Ionicons name="close" size={22} color={TEXT} />
                </TouchableOpacity>
              </View>

              {/* Search */}
              <View style={styles.searchRow}>
                <Ionicons name="search-outline" size={16} color={GRAY} style={styles.searchIcon} />
                <TextInput
                  ref={searchRef}
                  style={styles.searchInput}
                  placeholder="Search country or code…"
                  placeholderTextColor={GRAY}
                  value={search}
                  onChangeText={setSearch}
                  autoCorrect={false}
                  autoCapitalize="none"
                  clearButtonMode="while-editing"
                />
              </View>

              {/* Country list */}
              <FlatList
                data={filtered}
                keyExtractor={item => item.code}
                keyboardShouldPersistTaps="handled"
                ItemSeparatorComponent={() => <View style={styles.separator} />}
                renderItem={({ item }) => {
                  const active = item.code === selected.code;
                  return (
                    <TouchableOpacity
                      style={[styles.countryRow, active && { backgroundColor: `${accentColor}12` }]}
                      onPress={() => handleSelect(item)}
                      activeOpacity={0.7}
                    >
                      <Text style={styles.countryFlag}>{item.flag}</Text>
                      <Text style={[styles.countryName, active && { color: accentColor, fontWeight: '600' }]} numberOfLines={1}>
                        {item.name}
                      </Text>
                      <Text style={[styles.countryDial, active && { color: accentColor }]}>{item.dial}</Text>
                      {active && <Ionicons name="checkmark" size={16} color={accentColor} />}
                    </TouchableOpacity>
                  );
                }}
                ListEmptyComponent={
                  <View style={styles.emptyContainer}>
                    <Ionicons name="search-outline" size={32} color={BORDER} />
                    <Text style={styles.emptyText}>No countries found</Text>
                  </View>
                }
              />
            </SafeAreaView>
          </Pressable>
        </Pressable>
      </Modal>
    </View>
  );
}

const styles = StyleSheet.create({
  // Input row
  inputRow: {
    flexDirection: 'row',
    alignItems: 'center',
    borderWidth: 1.5,
    backgroundColor: BG,
    overflow: 'hidden',
  },
  selector: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  dialCode: {
    fontWeight: '600',
    color: TEXT,
  },
  divider: {
    width: 1,
    alignSelf: 'stretch',
    backgroundColor: BORDER,
    marginVertical: 8,
  },
  input: {
    flex: 1,
    color: TEXT,
  },

  // Error
  fieldError:     { flexDirection: 'row', alignItems: 'center' },
  fieldErrorText: { color: RED },

  // Modal overlay
  overlay: {
    flex: 1,
    backgroundColor: OVERLAY,
    justifyContent: 'flex-end',
  },
  sheet: {
    backgroundColor: WHITE,
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    maxHeight: '85%',
    minHeight: '50%',
  },
  sheetWeb: {
    alignSelf: 'center',
    width: '100%',
    maxWidth: 480,
    borderRadius: 16,
    marginBottom: 40,
    maxHeight: 560,
    minHeight: 400,
  } as any,

  // Sheet header
  sheetHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 20,
    paddingTop: 20,
    paddingBottom: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: BORDER,
  },
  sheetTitle: {
    fontSize: 17,
    fontWeight: '700',
    color: TEXT,
  },
  closeBtn: {
    padding: 4,
  },

  // Search
  searchRow: {
    flexDirection: 'row',
    alignItems: 'center',
    margin: 12,
    paddingHorizontal: 12,
    paddingVertical: 10,
    backgroundColor: BG,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: BORDER,
  },
  searchIcon:  { marginRight: 8 },
  searchInput: { flex: 1, fontSize: 14, color: TEXT },

  // Country row
  separator:   { height: StyleSheet.hairlineWidth, backgroundColor: BORDER, marginLeft: 56 },
  countryRow:  {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 13,
    gap: 12,
  },
  countryFlag: { fontSize: 22, width: 28 },
  countryName: { flex: 1, fontSize: 15, color: TEXT },
  countryDial: { fontSize: 14, color: GRAY, fontWeight: '500', marginRight: 4 },

  // Empty state
  emptyContainer: { alignItems: 'center', paddingVertical: 48, gap: 10 },
  emptyText:      { fontSize: 14, color: GRAY },
});
