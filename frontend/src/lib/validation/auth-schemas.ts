import { z } from 'zod';

export const loginSchema = z.object({
  email:    z.string().min(1, 'Email is required').email('Enter a valid email address'),
  password: z.string().min(1, 'Password is required').min(8, 'Minimum 8 characters'),
});

export type LoginFormData = z.infer<typeof loginSchema>;
