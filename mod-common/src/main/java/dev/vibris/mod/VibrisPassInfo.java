package dev.vibris.mod;

import com.google.common.collect.ImmutableSet;

public interface VibrisPassInfo {
	String vibris$name();

	ImmutableSet<Integer> vibris$flipsAfterPass();

	java.util.List<dev.vibris.api.ResourceCatalog.PassProgram> vibris$programs();
}
