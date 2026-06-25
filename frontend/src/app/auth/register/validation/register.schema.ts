import { z } from 'zod';
import { PHONE_REGEX } from './register.validators';

export const registerSchema = z
  .object({
    firstName:       z.string().min(1, 'First name is required'),
    lastName:        z.string().min(1, 'Last name is required'),
    email:           z.string().min(1, 'Email is required').email('Enter a valid email address'),
    phone:           z.string()
                       .min(1, 'Phone number is required')
                       .regex(PHONE_REGEX, 'Enter a valid phone number'),
    password:        z.string().min(8, 'Minimum 8 characters'),
    confirmPassword: z.string().min(1, 'Please confirm your password'),
    agreed:          z.literal(true, { error: 'You must accept the terms to continue' }),
  })
  .refine((d) => d.password === d.confirmPassword, {
    message: 'Passwords do not match',
    path:    ['confirmPassword'],
  });
