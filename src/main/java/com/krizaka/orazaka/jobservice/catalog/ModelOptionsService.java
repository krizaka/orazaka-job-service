package com.krizaka.orazaka.jobservice.catalog;

import com.krizaka.orazaka.persistence.domain.model.CatalogModelDto;
import com.krizaka.orazaka.persistence.domain.ports.inbound.CatalogModelManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Which models a person may choose for a category, and which one runs when they choose nothing.
 *
 * <p>The catalogue ({@code orazaka_models}) is the single source: the form's picker, the default a
 * blank field resolves to, and the check on a model id that arrives typed all read the same rows
 * through the same rules, so what a person is offered is what will run.
 *
 * <p><b>A model is runnable when its engine answers and a worker drains its queue.</b> The engine
 * is the provider's endpoint ({@link ProviderReachability}); the queue is the one a video job waits
 * in, drained by a worker that is alive in the registry ({@link QueueConsumers}). A self-hosted GPU
 * model with no GPU worker, or a hosted one with nobody to route it, is listed but greyed out with
 * a reason code, never offered as the default and refused by name when it is still submitted.
 *
 * <p><b>The default is a preference, not a promise.</b> The row flagged {@code is_default} is used
 * when it is runnable; when it is not (a local diffusion server that is off, a GPU host that is
 * stopped, no worker up), the first other runnable model takes over and the resolution carries a
 * notice, so the run succeeds on a model that exists and says which. When nothing answers the run
 * is refused by name instead of failing deep inside an engine with an opaque error
 * (krizaka/orazaka#203).
 */
@Service
public class ModelOptionsService {

  private final CatalogModelManager catalogModelManager;
  private final ProviderReachability reachability;
  private final QueueConsumers consumers;

  public ModelOptionsService(
      CatalogModelManager catalogModelManager,
      ProviderReachability reachability,
      QueueConsumers consumers) {
    this.catalogModelManager =
        Objects.requireNonNull(catalogModelManager, "CatalogModelManager cannot be null");
    this.reachability = Objects.requireNonNull(reachability, "ProviderReachability cannot be null");
    this.consumers = Objects.requireNonNull(consumers, "QueueConsumers cannot be null");
  }

  /**
   * The options of a category: every model the catalogue publishes ({@code status = available}),
   * the ones that answer first, the default marked on the one that will run.
   *
   * @param category the model category ({@code image}, {@code video}, {@code speech}, …)
   * @return the options, never null; empty when the category has no published model
   */
  public List<ModelOption> options(String category) {
    List<CatalogModelDto> offered = offered(category);
    Optional<CatalogModelDto> chosen = chooseDefault(offered);
    List<ModelOption> options = new ArrayList<>();
    for (CatalogModelDto model : offered) {
      boolean drained = drained(model);
      boolean up = drained && reachability.serves(model.providerName(), model.modelName());
      options.add(
          new ModelOption(
              model.modelName(),
              model.modelLabel(),
              model.providerName(),
              model.isHosted() ? "cloud" : "local",
              ModelOption.speedOf(model.typicalSeconds()),
              model.typicalSeconds(),
              model.priceCents(),
              model.description(),
              up,
              up ? null : unavailableReason(model, drained),
              up ? null : unavailableCode(model, drained),
              chosen.filter(c -> c.modelName().equals(model.modelName())).isPresent()));
    }
    options.sort(Comparator.comparing((ModelOption o) -> !o.available()));
    return List.copyOf(options);
  }

  /**
   * The model a step runs on.
   *
   * @param requested the model the caller named, possibly null or blank
   * @param category the model category
   * @param fallback the model used when the catalogue has none for the category at all
   * @return the model, with a notice when the default had to be replaced
   * @throws IllegalArgumentException when the caller named a model the category does not offer, or
   *     when no model of the category answers
   */
  public ModelResolution resolve(String requested, String category, String fallback) {
    List<CatalogModelDto> offered = offered(category);
    if (requested != null && !requested.isBlank()) {
      return ModelResolution.of(named(requested.strip(), category, offered));
    }
    if (offered.isEmpty()) {
      return catalogModelManager
          .getDefaultModelByCategory(category)
          .map(model -> ModelResolution.of(model.modelName()))
          .orElseGet(() -> ModelResolution.of(fallback));
    }
    CatalogModelDto preferred = declaredDefault(offered).orElse(null);
    if (preferred != null && runnable(preferred)) {
      return ModelResolution.of(preferred.modelName());
    }
    Optional<CatalogModelDto> instead =
        offered.stream()
            .filter(model -> preferred == null || !model.modelName().equals(preferred.modelName()))
            .filter(this::runnable)
            .findFirst();
    if (instead.isPresent()) {
      String notice =
          preferred == null
              ? null
              : preferred.modelLabel()
                  + " is not available right now; "
                  + instead.get().modelLabel()
                  + " was used instead.";
      return new ModelResolution(instead.get().modelName(), notice);
    }
    throw new IllegalArgumentException(
        "No "
            + category
            + " model is available right now"
            + (preferred == null
                ? ""
                : " (" + unavailableReason(preferred, drained(preferred)) + ")")
            + ". Try again in a few minutes.");
  }

  private String named(String requested, String category, List<CatalogModelDto> offered) {
    if (offered.isEmpty()) {
      return requested;
    }
    return offered.stream()
        .filter(model -> model.modelName().equalsIgnoreCase(requested))
        .findFirst()
        .map(CatalogModelDto::modelName)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Unknown "
                        + category
                        + " model '"
                        + requested
                        + "'. Choose one of: "
                        + String.join(
                            ", ", offered.stream().map(CatalogModelDto::modelName).toList())
                        + "."));
  }

  private List<CatalogModelDto> offered(String category) {
    return catalogModelManager.getModelsByCategory(category).stream()
        .filter(CatalogModelDto::isOffered)
        .toList();
  }

  private Optional<CatalogModelDto> declaredDefault(List<CatalogModelDto> offered) {
    return offered.stream().filter(model -> Boolean.TRUE.equals(model.isDefault())).findFirst();
  }

  /** The default when its engine answers, else the first model that does, else the declared one. */
  private Optional<CatalogModelDto> chooseDefault(List<CatalogModelDto> offered) {
    Optional<CatalogModelDto> declared = declaredDefault(offered);
    if (declared.isPresent() && runnable(declared.get())) {
      return declared;
    }
    Optional<CatalogModelDto> instead = offered.stream().filter(this::runnable).findFirst();
    return instead.isPresent() ? instead : declared;
  }

  private boolean drained(CatalogModelDto model) {
    return consumers.drained(model.category(), model.isHosted());
  }

  private boolean runnable(CatalogModelDto model) {
    return drained(model) && reachability.serves(model.providerName(), model.modelName());
  }

  /**
   * The routing key a job on this model is published with, when it is not the capability's own: a
   * model a provider hosts is routed by a cheap CPU worker through its own queue, a self-hosted one
   * by a GPU worker.
   *
   * @param modelName the model the step names (any spelling of the case)
   * @return the key; empty for a model the job service runs itself, an unknown one, or no name
   */
  public Optional<String> queueOf(String modelName) {
    if (modelName == null || modelName.isBlank()) {
      return Optional.empty();
    }
    return catalogModelManager.getAllModels().stream()
        .filter(model -> model.modelName().equalsIgnoreCase(modelName.strip()))
        .findFirst()
        .flatMap(model -> consumers.queueOf(model.category(), model.isHosted()));
  }

  /**
   * Why a model cannot run now, as a code a client words in its language (see {@link ModelOption}).
   */
  private static String unavailableCode(CatalogModelDto model, boolean drained) {
    if (!model.isHosted()) {
      return "gpu_offline";
    }
    return drained ? "provider_down" : "worker_offline";
  }

  private static String unavailableReason(CatalogModelDto model, boolean drained) {
    if (!drained) {
      return model.modelLabel()
          + (model.isHosted()
              ? " is not available right now (no worker is running its jobs)"
              : " needs a GPU, and none is online right now");
    }
    return model.modelLabel()
        + (model.isHosted()
            ? " is not answering (its provider could not be reached)"
            : " is not running (its engine could not be reached)");
  }
}
