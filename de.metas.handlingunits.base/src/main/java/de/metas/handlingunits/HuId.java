package de.metas.handlingunits;

import java.util.Collection;
import java.util.Set;

import org.adempiere.util.Check;

import com.google.common.collect.ImmutableSet;

import de.metas.lang.RepoIdAware;
import lombok.Value;

/*
 * #%L
 * de.metas.handlingunits.base
 * %%
 * Copyright (C) 2018 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

@Value
public class HuId implements RepoIdAware
{
	public static HuId ofRepoId(final int repoId)
	{
		return new HuId(repoId);
	}

	public static int toRepoId(final HuId huId)
	{
		return huId != null ? huId.getRepoId() : -1;
	}

	public static Set<HuId> ofRepoIds(final Collection<Integer> repoIds)
	{
		return repoIds.stream().map(HuId::ofRepoId).collect(ImmutableSet.toImmutableSet());
	}

	public static Set<Integer> toRepoIds(final Collection<HuId> huIds)
	{
		return huIds.stream().map(HuId::getRepoId).collect(ImmutableSet.toImmutableSet());
	}

	int repoId;

	private HuId(final int repoId)
	{
		this.repoId = Check.assumeGreaterThanZero(repoId, "repoId");
	}
}
