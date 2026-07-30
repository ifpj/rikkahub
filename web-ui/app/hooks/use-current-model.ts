import * as React from "react";

import { useCurrentAssistant } from "~/hooks/use-current-assistant";
import type { ConversationDto, ProviderModel, ProviderProfile } from "~/types";

export interface UseCurrentModelResult {
  currentModelId: string | null;
  currentModel: ProviderModel | null;
  currentProvider: ProviderProfile | null;
}

export function useCurrentModel(conversation?: ConversationDto | null): UseCurrentModelResult {
  const { settings, currentAssistant } = useCurrentAssistant();

  const currentModelId =
    (currentAssistant?.allowConversationModel ? conversation?.chatModelId : null) ??
    currentAssistant?.chatModelId ??
    settings?.chatModelId ??
    null;

  const { currentModel, currentProvider } = React.useMemo(() => {
    if (!settings || !currentModelId) {
      return {
        currentModel: null,
        currentProvider: null,
      };
    }

    for (const provider of settings.providers) {
      const model = provider.models.find((item) => item.id === currentModelId);
      if (model) {
        return {
          currentModel: model,
          currentProvider: provider,
        };
      }
    }

    return {
      currentModel: null,
      currentProvider: null,
    };
  }, [currentModelId, settings]);

  return {
    currentModelId,
    currentModel,
    currentProvider,
  };
}
