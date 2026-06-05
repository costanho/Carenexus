import { z } from 'zod';

export const loginSchema = z.object({
  email:    z.string().min(1, 'Email is required').email('Enter a valid email address'),
  password: z.string().min(1, 'Password is required').min(8, 'Minimum 8 characters'),
});

export const registerSchema = z
  .object({
    firstName:       z.string().min(1, 'First name is required'),
    lastName:        z.string().min(1, 'Last name is required'),
    email:           z.string().min(1, 'Email is required').email('Enter a valid email address'),
    phone:           z.string()
      .min(1, 'Phone number is required')
      .regex(/^\+\d+\s\d[\d\s\-()+]{4,}$/, 'Enter a valid phone number'),
    password:        z.string().min(8, 'Minimum 8 characters'),
    confirmPassword: z.string().min(1, 'Please confirm your password'),
    agreed:          z.literal(true, { error: 'You must accept the terms to continue' }),
  })
  .refine((d) => d.password === d.confirmPassword, {
    message: 'Passwords do not match',
    path:    ['confirmPassword'],
  });

export type LoginFormData    = z.infer<typeof loginSchema>;
export type RegisterFormData = z.infer<typeof registerSchema>;
