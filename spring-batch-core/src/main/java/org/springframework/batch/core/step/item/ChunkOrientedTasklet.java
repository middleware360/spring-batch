/*
 * Copyright 2006-2007 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.batch.core.step.item;

import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.repeat.ExitStatus;
import org.springframework.batch.repeat.RepeatCallback;
import org.springframework.batch.repeat.RepeatContext;
import org.springframework.batch.repeat.RepeatOperations;
import org.springframework.core.AttributeAccessor;

/**
 * Simplest possible implementation of {@link Tasklet} with no skipping or
 * recovering. Just delegates all calls to the provided {@link ItemReader} and
 * {@link ItemWriter}.
 * 
 * Provides extension points by protected {@link #read(StepContribution)} and
 * {@link #write(Chunk, StepContribution)} methods that can be overriden to
 * provide more sophisticated behaviour (e.g. skipping).
 * 
 * @author Dave Syer
 * @author Robert Kasanicky
 */
public class ChunkOrientedTasklet<T, S> extends AbstractItemProcessingTasklet<T,S> {

	private static final String INPUT_BUFFER_KEY = "INPUT_BUFFER_KEY";

	private static final String OUTPUT_BUFFER_KEY = "OUTPUT_BUFFER_KEY";

	private final RepeatOperations repeatOperations;

	/**
	 * @param itemReader
	 * @param itemProcessor
	 * @param itemWriter
	 * @param repeatOperations
	 */
	public ChunkOrientedTasklet(ItemReader<? extends T> itemReader,
			ItemProcessor<? super T, ? extends S> itemProcessor, ItemWriter<? super S> itemWriter,
			RepeatOperations repeatOperations) {
		super(itemReader, itemProcessor, itemWriter);
		this.repeatOperations = repeatOperations;
	}

	/**
	 * Get the next item from {@link #read(StepContribution)} and if not null
	 * pass the item to {@link #write(Chunk, StepContribution)}. If the
	 * {@link ItemProcessor} returns null, the write is omitted and another item
	 * taken from the reader.
	 * 
	 * @see org.springframework.batch.core.step.tasklet.Tasklet#execute(org.springframework.batch.core.StepContribution,
	 * AttributeAccessor)
	 */
	public ExitStatus execute(final StepContribution contribution, AttributeAccessor attributes) throws Exception {

		// TODO: check flags to see if these need to be saved or not (e.g. JMS not)
		final Chunk<ItemWrapper<T>> inputs = getInputBuffer(attributes);
		final Chunk<S> outputs = getOutputBuffer(attributes);

		ExitStatus result = ExitStatus.CONTINUABLE;

		if (inputs.isEmpty() && outputs.isEmpty()) {

			result = repeatOperations.iterate(new RepeatCallback() {
				public ExitStatus doInIteration(final RepeatContext context) throws Exception {
					ItemWrapper<T> item = read(contribution);
					contribution.incrementReadSkipCount(item.getSkipCount());
					if (item.getItem() == null) {
						return ExitStatus.FINISHED;
					}
					inputs.add(item);
					contribution.incrementReadCount();
					return ExitStatus.CONTINUABLE;
				}
			});
			
			// If there is no input we don't have to do anything more
			if (inputs.isEmpty()) {
				return result;
			}
			
			storeInputs(attributes, inputs);

		}
		
		if (!inputs.isEmpty()) {
			process(contribution, inputs, outputs);
		}

		storeOutputsAndClearInputs(attributes, outputs, contribution);

		// TODO: use ItemWriter interface properly
		// TODO: make sure exceptions get handled by the appropriate handler
		write(outputs, contribution);

		// On successful completion clear the attributes to signal that there is
		// no more processing
		if (outputs.isEmpty()) {
			clearAll(attributes);
		}

		return result;

	}

	/**
	 * @param contribution current context
	 * @return next item for writing
	 */
	protected ItemWrapper<T> read(StepContribution contribution) throws Exception {
		return new ItemWrapper<T>(doRead());
	}

	/**
	 * 
	 * @param inputs the items to process
	 * @param outputs the items to write
	 * @param contribution current context
	 */
	protected void process(StepContribution contribution, Chunk<ItemWrapper<T>> inputs, Chunk<S> outputs) throws Exception {
		int filtered = 0;
		for (ItemWrapper<T> wrapper : inputs) {
			
			S output = doProcess(wrapper.getItem());
			if (output != null) {
				outputs.add(output);
			} else {
				filtered++;
			}
		}
		contribution.incrementFilterCount(filtered);
		inputs.clear();
	}

	/**
	 * 
	 * @param chunk the items to write
	 * @param contribution current context
	 */
	protected void write(Chunk<S> chunk, StepContribution contribution) throws Exception {
		doWrite(chunk.getItems());
		contribution.incrementWriteCount(chunk.size());
		chunk.clear();
	}

	/**
	 * @param attributes
	 */
	private void clearInputs(AttributeAccessor attributes) {
		attributes.removeAttribute(INPUT_BUFFER_KEY);
	}

	/**
	 * @param attributes
	 * @param inputs
	 */
	private void storeInputs(AttributeAccessor attributes, Chunk<ItemWrapper<T>> inputs) {
		store(attributes, INPUT_BUFFER_KEY, inputs);
	}

	/**
	 * Savepoint at end of processing and before writing. The processed items
	 * ready for output are stored so that if writing fails they can be picked
	 * up again in the next try. The inputs are finished with so we can clear
	 * their attribute.
	 * 
	 * @param attributes
	 * @param outputs
	 */
	private void storeOutputsAndClearInputs(AttributeAccessor attributes, Chunk<S> outputs,
			StepContribution contribution) {
		store(attributes, OUTPUT_BUFFER_KEY, outputs);
		clearInputs(attributes);
	}

	/**
	 * @param attributes
	 * @param inputBufferKey
	 * @param outputs
	 */
	private <W> void store(AttributeAccessor attributes, String key, W value) {
		attributes.setAttribute(key, value);
	}

	private void clearAll(AttributeAccessor attributes) {
		for (String key : attributes.attributeNames()) {
			attributes.removeAttribute(key);
		}
	}

	private Chunk<ItemWrapper<T>> getInputBuffer(AttributeAccessor attributes) {
		return getBuffer(attributes, INPUT_BUFFER_KEY);
	}

	private Chunk<S> getOutputBuffer(AttributeAccessor attributes) {
		return getBuffer(attributes, OUTPUT_BUFFER_KEY);
	}

	/**
	 * @param attributes
	 * @param inputBufferKey
	 * @return
	 */
	private <W> Chunk<W> getBuffer(AttributeAccessor attributes, String key) {
		if (!attributes.hasAttribute(key)) {
			return new Chunk<W>();
		}
		@SuppressWarnings("unchecked")
		Chunk<W> resource = (Chunk<W>) attributes.getAttribute(key);
		return resource;
	}

}