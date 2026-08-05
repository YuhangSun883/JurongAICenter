'use client';

import { useEffect, useState } from 'react';
import { LoginDialog } from './LoginDialog';

export function LoginGate() {
  const [open, setOpen] = useState(false);

  useEffect(() => {
    setOpen(!localStorage.getItem('token'));
  }, []);

  if (!open) return null;

  return <LoginDialog onClose={() => setOpen(false)} />;
}
