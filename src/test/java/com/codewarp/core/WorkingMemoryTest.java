package com.codewarp.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkingMemoryTest {

    @Test
    void appendAndSnapshotReturnCurrentMessages() {
        WorkingMemory memory = new WorkingMemory();

        memory.append(new Message.User("hello"));

        assertEquals(1, memory.size());
        assertEquals(List.of(new Message.User("hello")), memory.snapshot());
    }

    @Test
    void snapshotCannotModifyInternalMessages() {
        WorkingMemory memory = new WorkingMemory();
        memory.append(new Message.User("hello"));
        List<Message> snapshot = memory.snapshot();

        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(new Message.User("bad")));
        assertEquals(1, memory.size());
    }

    @Test
    void sliceFromReturnsOnlyNewMessages() {
        WorkingMemory memory = new WorkingMemory();
        memory.append(new Message.User("old"));
        int start = memory.size();
        memory.append(new Message.User("new"));
        memory.append(new Message.Assistant("done", List.of(), null));

        assertEquals(
                List.of(new Message.User("new"), new Message.Assistant("done", List.of(), null)),
                memory.sliceFrom(start)
        );
    }

    @Test
    void rollbackRemovesMessagesAfterIndex() {
        WorkingMemory memory = new WorkingMemory();
        memory.append(new Message.User("old"));
        int start = memory.size();
        memory.append(new Message.User("new"));
        memory.append(new Message.Assistant("done", List.of(), null));

        memory.rollbackTo(start);

        assertEquals(List.of(new Message.User("old")), memory.snapshot());
    }

    @Test
    void clearRemovesAllMessages() {
        WorkingMemory memory = new WorkingMemory();
        memory.append(new Message.User("hello"));

        memory.clear();

        assertEquals(0, memory.size());
    }

    @Test
    void restoreKeepsMessagesAndTranscriptUuids() {
        WorkingMemory memory = new WorkingMemory();

        memory.restore(List.of(new WorkingMemory.Entry(new Message.User("hello"), "uuid-1")));

        assertEquals(List.of(new Message.User("hello")), memory.snapshot());
        assertEquals("uuid-1", memory.transcriptUuidAt(0));
    }

    @Test
    void invalidIndexesAreRejected() {
        WorkingMemory memory = new WorkingMemory();

        assertThrows(IllegalArgumentException.class, () -> memory.sliceFrom(-1));
        assertThrows(IllegalArgumentException.class, () -> memory.sliceFrom(1));
        assertThrows(IllegalArgumentException.class, () -> memory.rollbackTo(-1));
        assertThrows(IllegalArgumentException.class, () -> memory.rollbackTo(1));
    }
}
