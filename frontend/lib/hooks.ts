"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { ApiError, api } from "./api";
import type { SessionInfo } from "./types";

/** Current session. A 401 is treated as "signed out", not an error state. */
export function useSession() {
  return useQuery<SessionInfo | null>({
    queryKey: ["session"],
    queryFn: async () => {
      try {
        return await api.me();
      } catch (error) {
        if (error instanceof ApiError && (error.status === 401 || error.status === 403)) return null;
        throw error;
      }
    },
    staleTime: 60_000,
    retry: false,
  });
}

/** Redirects to /login when the session is definitively absent. */
export function useRequireSession() {
  const router = useRouter();
  const session = useSession();
  useEffect(() => {
    if (session.isSuccess && session.data === null) router.replace("/login");
  }, [session.isSuccess, session.data, router]);
  return session;
}

export function useCapability(capability: string): boolean {
  const session = useSession();
  return session.data?.capabilities.includes(capability) ?? false;
}

export function useLogout() {
  const router = useRouter();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.logout(),
    onSettled: () => {
      queryClient.clear();
      router.replace("/login");
    },
  });
}

export function useDebounced<T>(value: T, delay = 300): T {
  const [debounced, setDebounced] = useStateSafe(value);
  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(value), delay);
    return () => window.clearTimeout(timer);
  }, [value, delay, setDebounced]);
  return debounced;
}

function useStateSafe<T>(initial: T) {
  return useState<T>(initial);
}

export function useSearchParamState(key: string): [string | null, (value: string | null) => void] {
  const router = useRouter();
  const read = useCallback((): string | null => {
    if (typeof window === "undefined") return null;
    return new URLSearchParams(window.location.search).get(key);
  }, [key]);
  const [value, setValue] = useStateSafe<string | null>(null);
  useEffect(() => setValue(read()), [read, setValue]);
  const update = useCallback(
    (next: string | null) => {
      const params = new URLSearchParams(window.location.search);
      if (next === null) params.delete(key);
      else params.set(key, next);
      const query = params.toString();
      router.replace(`${window.location.pathname}${query ? `?${query}` : ""}`, { scroll: false });
      setValue(next);
    },
    [key, router, setValue],
  );
  return [value, update];
}
