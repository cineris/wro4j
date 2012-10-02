package ro.isdc.wro.model.resource.support;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.AutoCloseInputStream;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ro.isdc.wro.WroRuntimeException;
import ro.isdc.wro.cache.CacheEntry;
import ro.isdc.wro.cache.CacheStrategy;
import ro.isdc.wro.cache.ContentHashEntry;
import ro.isdc.wro.model.factory.WroModelFactory;
import ro.isdc.wro.model.group.Group;
import ro.isdc.wro.model.group.Inject;
import ro.isdc.wro.model.group.processor.Injector;
import ro.isdc.wro.model.resource.Resource;
import ro.isdc.wro.model.resource.ResourceType;
import ro.isdc.wro.model.resource.locator.factory.UriLocatorFactory;
import ro.isdc.wro.model.resource.processor.ResourcePreProcessor;
import ro.isdc.wro.model.resource.processor.decorator.ExceptionHandlingProcessorDecorator;
import ro.isdc.wro.model.resource.processor.impl.css.AbstractCssImportPreProcessor;
import ro.isdc.wro.model.resource.processor.impl.css.CssImportPreProcessor;
import ro.isdc.wro.model.resource.support.hash.HashStrategy;
import ro.isdc.wro.util.StopWatch;


/**
 * A runnable responsible for watching if any resources were changed and invalidate the cache entry for the group
 * containing obsolete resources. This class is thread-safe.
 * 
 * @author Alex Objelean
 * @created 06 Aug 2012
 * @since 1.4.8
 */
public class ResourceWatcher {
  private static final Logger LOG = LoggerFactory.getLogger(ResourceWatcher.class);
  @Inject
  private CacheStrategy<CacheEntry, ContentHashEntry> cacheStrategy;
  @Inject
  private WroModelFactory modelFactory;
  @Inject
  private UriLocatorFactory locatorFactory;
  @Inject
  private HashStrategy hashStrategy;
  @Inject
  private Injector injector;
  /**
   * Contains the resource uri's with associated hash values retrieved from last successful check.
   */
  private final Map<String, String> previousHashes = new ConcurrentHashMap<String, String>();
  /**
   * Contains the resource uri's with associated hash values retrieved from currently performed check.
   */
  private final Map<String, String> currentHashes = new ConcurrentHashMap<String, String>();
  /**
   * Check if resources from a group were changed. If a change is detected, the changeListener will be invoked.
   * 
   * @param cacheEntry
   *          the cache key which was requested. The key contains the groupName which has to be checked for changes.
   */
  public void check(final CacheEntry cacheEntry) {
    Validate.notNull(cacheEntry);
    LOG.debug("ResourceWatcher started...");
    final StopWatch watch = new StopWatch();
    watch.start("detect changes");
    try {
      final Group group = modelFactory.create().getGroupByName(cacheEntry.getGroupName());
      if (isGroupChanged(group)) {
        onGroupChanged(cacheEntry);
      }
      // cleanUp
      for (Entry<String, String> entry : currentHashes.entrySet()) {
        previousHashes.put(entry.getKey(), entry.getValue());
      }
      currentHashes.clear();
    } catch (Exception e) {
      LOG.error("Exception while checking for resource changes because: {}", e.getMessage());
      LOG.debug("[FAIL] detecting resource change ", e);
    } finally {
      watch.stop();
      LOG.debug("resource watcher info: {}", watch.prettyPrint());
    }
  }
  
  private boolean isGroupChanged(final Group group) {
    LOG.debug("Checking if group {} is changed..", group.getName());
    // TODO run the check in parallel?
    final List<Resource> resources = group.getResources();
    boolean isChanged = false;
    for (Resource resource : resources) {
      if (isChanged = isChanged(resource)) {
        onResourceChanged(resource);
        break;
      }
    }
    return isChanged;
  }
  
  
  /**
   * Invoked when the change of the resource is detected.
   * @param resource the {@link Resource} which changed. 
   * @VisibleForTesting
   */
  void onResourceChanged(final Resource resource) {
  }

  /**
   * Invoked when a resource change detected.
   * 
   * @param key
   *          {@link CacheEntry} which has to be invalidated because the corresponding group contains stale resources.
   * @VisibleForTesting
   */
  void onGroupChanged(final CacheEntry key) {
    LOG.debug("detected change for cacheKey: {}", key);
    cacheStrategy.put(key, null);
  }

  /**
   * Check if the resource was changed from previous run. The implementation uses resource content digest (hash) to
   * check for change.
   * 
   * @param resource
   *          the {@link Resource} to check.
   * @return true if the resource was changed.
   */
  private boolean isChanged(final Resource resource) {
    LOG.debug("Check change for resource {}", resource.getUri());
    try {
      final String uri = resource.getUri();
      final String currentHash = getCurrentHash(uri);
      final String previousHash = previousHashes.get(uri);
      //using AtomicBoolean because we need to mutate this variable inside an anonymous class.
      final AtomicBoolean changeDetected = new AtomicBoolean(false);
      changeDetected.set(previousHash != null ? !previousHash.equals(currentHash) : false);
      if (!changeDetected.get() && resource.getType() == ResourceType.CSS) {
        final Reader reader = new InputStreamReader(locatorFactory.locate(uri));
        LOG.debug("Check @import directive from {}", resource);
        //detect changes in imported resources.
        createCssImportProcessor(resource, changeDetected).process(resource, reader, new StringWriter());
      }
      return changeDetected.get();
    } catch (IOException e) {
      LOG.debug("[FAIL] Cannot check {} resource (Exception message: {}). Assuming it is unchanged...", resource,
          e.getMessage());
      return false;
    }
  }

  private ResourcePreProcessor createCssImportProcessor(final Resource resource, final AtomicBoolean changeDetected) {
    final ResourcePreProcessor cssImportProcessor = new AbstractCssImportPreProcessor() {
      protected void onImportDetected(final String importedUri) {
        //avoid recursivity
        //if (!resource.getUri().equals(importedUri)) {
          LOG.debug("Found @import {}", importedUri);
          boolean isImportChanged = isChanged(Resource.create(importedUri, ResourceType.CSS));
          LOG.debug("\tisImportChanged: {}", isImportChanged);
          if (isImportChanged) {
            changeDetected.set(true);
            // no need to continue
            throw new WroRuntimeException("Change detected. No need to continue processing");
          }
        //}
      };
      @Override
      protected String doTransform(final String cssContent, final List<Resource> foundImports)
          throws IOException {
        //no need to build the content, since we are interested in finding imported resources only
        return "";
      }
      @Override
      public String toString() {
        return CssImportPreProcessor.class.getSimpleName();
      }
    };
    /**
     * Ignore processor failure, since we are interesting in detecting change only. A failure is treated as lack of
     * change.
     */
    final ResourcePreProcessor processor = new ExceptionHandlingProcessorDecorator(cssImportProcessor) {
      @Override
      protected boolean isIgnoreFailingProcessor() {
        return true;
      }
    };
    injector.inject(processor);
    return processor;
  }
  
  /**
   * @param uri
   *          of the resource to get the hash for.
   * @return the hash for a given resource uri.
   * @throws IOException
   */
  private String getCurrentHash(final String uri)
      throws IOException {
    String currentHash = currentHashes.get(uri);
    if (currentHash == null) {
      LOG.debug("Checking if resource {} is changed..", uri);
      currentHash = hashStrategy.getHash(new AutoCloseInputStream(locatorFactory.locate(uri)));
      currentHashes.put(uri, currentHash);
    }
    return currentHash;
  }
  
  /**
   * @return the map storing the hash of accumulated resources from previous runs.
   * @VisibleForTesting
   */
  Map<String, String> getPreviousHashes() {
    return Collections.unmodifiableMap(previousHashes);
  }
  
  /**
   * @return the map storing the hash of resources from current run.
   * @VisibleForTesting
   */
  Map<String, String> getCurrentHashes() {
    return Collections.unmodifiableMap(currentHashes);
  }
}
