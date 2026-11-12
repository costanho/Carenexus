import { expect, test } from '@playwright/test';
import {
  claimFormSchema, feeFormSchema, invoiceFormSchema, parseMoneyInput, paymentFormSchema, toInitiatePaymentRequest,
  toUpdateFeesRequest,
} from '../src/lib/validation/billing';

// How the billing money fields read what people type (src/lib/validation/billing.ts). No browser and no stack:
// these run in the test runner itself. Regression: a comma typed as the decimal separator ("12,50" on a phone set to
// a comma-decimal locale) was stripped as a thousands separator, so a payment of 12.50 went out as 1250.

test.describe('billing money input', () => {
  test('a comma followed by groups of three digits is a thousands separator', () => {
    expect(parseMoneyInput('1,500')).toBe(1500);
    expect(parseMoneyInput('1,500.00')).toBe(1500);
    expect(parseMoneyInput('1,500.5')).toBe(1500.5);
    expect(parseMoneyInput('12,345,678.90')).toBe(12345678.9);
    expect(parseMoneyInput(' 1 500.00 ')).toBe(1500);
  });

  test('a single comma followed by one or two digits is a decimal comma', () => {
    expect(parseMoneyInput('12,50')).toBe(12.5);
    expect(parseMoneyInput('12,5')).toBe(12.5);
    expect(parseMoneyInput('1500,50')).toBe(1500.5);
    expect(parseMoneyInput('0,99')).toBe(0.99);
  });

  test('other commas are not guessed at', () => {
    for (const text of ['1.500,50', '1,50,000', '1,5,0', '12,', ',50', '1500,505', '1,500,5']) {
      expect(parseMoneyInput(text), text).toBeNull();
    }
  });

  test('plain amounts are unchanged', () => {
    expect(parseMoneyInput('2500')).toBe(2500);
    expect(parseMoneyInput('2500.25')).toBe(2500.25);
    expect(parseMoneyInput('')).toBeNull();
    expect(parseMoneyInput('2500.255')).toBeNull();
  });

  test('the Pay form sends 12.50 for "12,50", not 1250', () => {
    const schema = paymentFormSchema(5000, 'KES');
    const values = schema.parse({ method: 'MPESA', paymentMethodId: '', amount: '12,50', payerPhone: '+254712345678' });
    expect(toInitiatePaymentRequest(values).amount).toBe(12.5);
  });

  test('a decimal comma is checked against the balance as the amount it means; mixed separators are refused', () => {
    // "1500,50" is 1500.50, within a 2,000 balance (read as 150050 it was refused as above the balance).
    const pay = paymentFormSchema(2000, 'KES');
    expect(pay.safeParse({ method: 'MPESA', paymentMethodId: '', amount: '1500,50', payerPhone: '+254712345678' }).success).toBe(true);
    const bad = pay.safeParse({ method: 'MPESA', paymentMethodId: '', amount: '1.500,50', payerPhone: '+254712345678' });
    expect(bad.success).toBe(false);
    expect(bad.error?.issues[0]?.message).toBe('Enter an amount with at most 2 decimals, for example 1500 or 1500.50');
  });

  test('the fee editor, invoice and claim forms read a decimal comma the same way', () => {
    const fees = feeFormSchema.parse({ VIDEO: '1500,50', IN_PERSON: '2,000', PHONE: '' });
    expect(toUpdateFeesRequest(fees).fees).toEqual([
      { appointmentType: 'VIDEO', amount: 1500.5 },
      { appointmentType: 'IN_PERSON', amount: 2000 },
      { appointmentType: 'PHONE', amount: null },
    ]);

    const invoice = invoiceFormSchema.safeParse({
      lineItems: [{ description: 'Consultation', quantity: '1', unitPrice: '100,00' }],
      discount: '10,50',
      notes: '',
    });
    expect(invoice.success).toBe(true);

    expect(claimFormSchema(100, 'KES').safeParse({
      insuranceProvider: 'Insurer', policyNumber: 'POL-1', memberNumber: '', claimAmount: '99,99', documentIds: [],
    }).success).toBe(true);
  });
});
