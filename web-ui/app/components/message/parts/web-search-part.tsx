import { LoaderCircle, Search } from "lucide-react";
import { useTranslation } from "react-i18next";

import type { WebSearchPart as UIWebSearchPart } from "~/types";

import { ChainOfThoughtStep } from "../chain-of-thought";

interface WebSearchPartProps {
  search: UIWebSearchPart;
  isFirst?: boolean;
  isLast?: boolean;
}

export function WebSearchPart({ search, isFirst, isLast }: WebSearchPartProps) {
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
    <ChainOfThoughtStep
      isFirst={isFirst}
      isLast={isLast}
      icon={
        completed ? (
          <Search className="size-4 text-primary" />
        ) : (
          <LoaderCircle className="size-4 animate-spin text-primary" />
        )
      }
      label={<span className={completed ? "text-xs" : "animate-pulse text-xs"}>{label}</span>}
      extra={
        search.sources.length > 0 ? (
          <span className="shrink-0 text-xs text-muted-foreground">
            {t("message_parts.web_search_sources", { count: search.sources.length })}
          </span>
        ) : undefined
      }
    />
  );
}
