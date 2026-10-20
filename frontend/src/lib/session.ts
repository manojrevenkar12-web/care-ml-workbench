import { useQuery } from "@tanstack/react-query";
import { useParams } from "react-router-dom";
import { api } from "./api";
import type { Project } from "./types";

export function useMe() {
  return useQuery({ queryKey: ["me"], queryFn: api.me, staleTime: 60_000 });
}

export function useLabels() {
  return useQuery({ queryKey: ["labels"], queryFn: api.labels, staleTime: Infinity });
}

/** The project in the URL, with the signed-in user's permissions on it. */
export function useProject(): Project | undefined {
  const { project } = useParams();
  const me = useMe();
  return me.data?.projects.find((p) => p.id === project);
}
