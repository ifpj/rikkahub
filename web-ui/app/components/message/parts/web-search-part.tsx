import { LoaderCircle, Search } from "lucide-react";
import { useTranslation } from "react-i18next";

import type { WebSearchPart as UIWebSearchPart } from "~/types";

interface WebSearchPartProps {
  search: UIWebSearchPart;
}

export function WebSearchPart({ search }: WebSearchPartProps) {
  const { t } = useTranslation("message");
  const completed = search.status === "completed";
  const detail = search.query ?? search.url ?? search.pattern;
  const label = completed
    ? detail
      ? t("message_parts.web_search_completed_for", { query: detail })
      : t("message_parts.web_search_completed")
    : detail
      ? t("message_parts.web_search_searching_for", { query: detail })
      : t("message_parts.web_search_searching");

  return (
    <div className="my-1 flex items-center gap-2 py-1 text-xs text-muted-foreground">
      {completed ? (
        <Search className="h-4 w-4 shrink-0 text-primary" />
      ) : (
        <LoaderCircle className="h-4 w-4 shrink-0 animate-spin text-primary" />
      )}
      <span className={completed ? undefined : "animate-pulse"}>{label}</span>
      {search.sources.length > 0 ? (
        <span className="shrink-0">{t("message_parts.web_search_sources", { count: search.sources.length })}</span>
      ) : null}
    </div>
  );
}
