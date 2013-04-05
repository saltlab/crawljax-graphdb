package com.crawljax.core.state;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import net.jcip.annotations.GuardedBy;

import org.apache.commons.math.stat.descriptive.moment.Mean;
import org.jgrapht.DirectedGraph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.DijkstraShortestPath;
import org.jgrapht.alg.KShortestPaths;
import org.jgrapht.graph.DirectedMultigraph;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.*;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

/**
 * The State-Flow Graph is a multi-edge directed graph with states (StateVetex)
 * on the vertices and clickables (Eventable) on the edges.
 */
@SuppressWarnings("serial")
public class StateFlowGraph implements Serializable {

	private static final Logger LOG = LoggerFactory
			.getLogger(StateFlowGraph.class.getName());

	// The directory path for saving the graph-db created by neo4j for
	// persisting the state flow graph

	// private static final String DB_PATH = "target/state-flow-graph-db";
	//
	// // the relationship between a source vertex and the destination vertex
	//
	// private static enum RelTypes implements RelationshipType
	// {
	// TRANSITIONS_TO
	// }
	//
	// private GraphDatabaseService sfgDb ;

	private static void registerShutdownHook(
			final GraphDatabaseService graphDatabaseService) {
		// Registering a shutdown hook for the db instance so as to
		// shut it down nicely when the VM exits

		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				graphDatabaseService.shutdown();
			}
		});
	}

	public static byte[] serializeStateVertex(StateVertex stateVertex) {

		byte[] serializedStateVertex = null;

		// this an output stream that does not require writing to the file and
		// instead
		// the output stream is stored in a buffer
		// we use this class to utilize the Java serialization api which writes
		// and reads
		// object to and from streams

		ByteArrayOutputStream baos = new ByteArrayOutputStream();

		try {

			ObjectOutputStream oos = new ObjectOutputStream(baos);

			// seriliazing the stateVertex object to the stream

			oos.writeObject(stateVertex);

			// converting the byte array to UTF-8 string for portability reasons

			serializedStateVertex = baos.toByteArray();

			// closing streams

			oos.close();
			baos.close();

		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return serializedStateVertex;
	}

	public static StateVertex deserializeStateVertex(
			byte[] serializedStateVertex) {
		// the returned value

		StateVertex deserializedSV = null;

		try {

			ByteArrayInputStream bais = new ByteArrayInputStream(
					serializedStateVertex);

			ObjectInputStream ois = new ObjectInputStream(bais);

			deserializedSV = (StateVertex) ois.readObject();

			// clsoing streams

			ois.close();
			bais.close();

		} catch (UnsupportedEncodingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return deserializedSV;

	}

	private final DirectedGraph<StateVertex, Eventable> sfg;

	/**
	 * Intermediate counter for the number of states, not relaying on
	 * getAllStates.size() because of Thread-safety.
	 */
	private final AtomicInteger stateCounter = new AtomicInteger();

	private final StateVertex initialState;

	/**
	 * The constructor.
	 * 
	 * @param initialState
	 *            the state to start from.
	 */
	public StateFlowGraph(StateVertex initialState) {
		Preconditions.checkNotNull(initialState);

		// creating the graph db

		// sfgDb = new GraphDatabaseFactory().newEmbeddedDatabase(DB_PATH);
		//
		// // adding a shutdown hook to ensure the db will be shut down even if
		// // the program breaks
		//
		// registerShutdownHook(sfgDb);
		//

		sfg = new DirectedMultigraph<>(Eventable.class);
		//
		// // add the first node to the graph

		sfg.addVertex(initialState);
		this.initialState = initialState;
	}

	public static void addAnode(StateVertex state) {
		byte[] serializedSV = StateFlowGraph.serializeStateVertex(state);

	}

	/**
	 * Adds a state (as a vertix) to the State-Flow Graph if not already
	 * present. More formally, adds the specified vertex, v, to this graph if
	 * this graph contains no vertex u such that u.equals(v). If this graph
	 * already contains such vertex, the call leaves this graph unchanged and
	 * returns false. In combination with the restriction on constructors, this
	 * ensures that graphs never contain duplicate vertices. Throws
	 * java.lang.NullPointerException - if the specified vertex is null. This
	 * method automatically updates the state name to reflect the internal state
	 * counter.
	 * 
	 * @param stateVertix
	 *            the state to be added.
	 * @return the clone if one is detected null otherwise.
	 * @see org.jgrapht.Graph#addVertex(Object)
	 */
	public StateVertex addState(StateVertex stateVertix) {
		return addState(stateVertix, true);
	}

	/**
	 * Adds a state (as a vertix) to the State-Flow Graph if not already
	 * present. More formally, adds the specified vertex, v, to this graph if
	 * this graph contains no vertex u such that u.equals(v). If this graph
	 * already contains such vertex, the call leaves this graph unchanged and
	 * returns false. In combination with the restriction on constructors, this
	 * ensures that graphs never contain duplicate vertices. Throws
	 * java.lang.NullPointerException - if the specified vertex is null.
	 * 
	 * @param stateVertix
	 *            the state to be added.
	 * @param correctName
	 *            if true the name of the state will be corrected according to
	 *            the internal state counter.
	 * @return the clone if one is detected null otherwise.
	 * @see org.jgrapht.Graph#addVertex(Object)
	 */
	@GuardedBy("sfg")
	public StateVertex addState(StateVertex stateVertix, boolean correctName) {
		synchronized (sfg) {
			if (!sfg.addVertex(stateVertix)) {
				// Graph already contained the vertix
				LOG.debug("Graph already contained vertex {}", stateVertix);
				return this.getStateInGraph(stateVertix);
			} else {
				int count = stateCounter.incrementAndGet();
				LOG.debug("Number of states is now {}", count);
				if (correctName) {
					correctStateName(stateVertix);
				}
				return null;
			}
		}
	}

	private void correctStateName(StateVertex stateVertix) {
		// the -1 is for the "index" state.
		int totalNumberOfStates = this.getAllStates().size() - 1;
		String correctedName = makeStateName(totalNumberOfStates,
				stateVertix.isGuidedCrawling());
		if (!"index".equals(stateVertix.getName())
				&& !stateVertix.getName().equals(correctedName)) {
			LOG.info("Correcting state name from {}  to {}",
					stateVertix.getName(), correctedName);
			stateVertix.setName(correctedName);
		}
	}

	/**
	 * Adds the specified edge to this graph, going from the source vertex to
	 * the target vertex. More formally, adds the specified edge, e, to this
	 * graph if this graph contains no edge e2 such that e2.equals(e). If this
	 * graph already contains such an edge, the call leaves this graph unchanged
	 * and returns false. Some graphs do not allow edge-multiplicity. In such
	 * cases, if the graph already contains an edge from the specified source to
	 * the specified target, than this method does not change the graph and
	 * returns false. If the edge was added to the graph, returns true. The
	 * source and target vertices must already be contained in this graph. If
	 * they are not found in graph IllegalArgumentException is thrown.
	 * 
	 * @param sourceVert
	 *            source vertex of the edge.
	 * @param targetVert
	 *            target vertex of the edge.
	 * @param clickable
	 *            the clickable edge to be added to this graph.
	 * @return true if this graph did not already contain the specified edge.
	 * @see org.jgrapht.Graph#addEdge(Object, Object, Object)
	 */
	@GuardedBy("sfg")
	public boolean addEdge(StateVertex sourceVert, StateVertex targetVert,
			Eventable clickable) {
		synchronized (sfg) {
			if (sfg.containsEdge(sourceVert, targetVert)
					&& sfg.getAllEdges(sourceVert, targetVert).contains(
							clickable)) {
				return false;
			} else {
				return sfg.addEdge(sourceVert, targetVert, clickable);
			}
		}
	}

	/**
	 * @return the string representation of the graph.
	 * @see org.jgrapht.DirectedGraph#toString()
	 */
	@Override
	public String toString() {
		synchronized (sfg) {
			return sfg.toString();
		}
	}

	/**
	 * Returns a set of all clickables outgoing from the specified vertex.
	 * 
	 * @param stateVertix
	 *            the state vertix.
	 * @return a set of the outgoing edges (clickables) of the stateVertix.
	 * @see org.jgrapht.DirectedGraph#outgoingEdgesOf(Object)
	 */
	public Set<Eventable> getOutgoingClickables(StateVertex stateVertix) {
		return sfg.outgoingEdgesOf(stateVertix);
	}

	/**
	 * Returns a set of all edges incoming into the specified vertex.
	 * 
	 * @param stateVertix
	 *            the state vertix.
	 * @return a set of the incoming edges (clickables) of the stateVertix.
	 * @see org.jgrapht.DirectedGraph#incomingEdgesOf(Object)
	 */
	public Set<Eventable> getIncomingClickable(StateVertex stateVertix) {
		return sfg.incomingEdgesOf(stateVertix);
	}

	/**
	 * Returns the set of outgoing states.
	 * 
	 * @param stateVertix
	 *            the state.
	 * @return the set of outgoing states from the stateVertix.
	 */
	public Set<StateVertex> getOutgoingStates(StateVertex stateVertix) {
		final Set<StateVertex> result = new HashSet<StateVertex>();

		for (Eventable c : getOutgoingClickables(stateVertix)) {
			result.add(sfg.getEdgeTarget(c));
		}

		return result;
	}

	/**
	 * @param clickable
	 *            the edge.
	 * @return the target state of this edge.
	 */
	public StateVertex getTargetState(Eventable clickable) {
		return sfg.getEdgeTarget(clickable);
	}

	/**
	 * Is it possible to go from s1 -> s2?
	 * 
	 * @param source
	 *            the source state.
	 * @param target
	 *            the target state.
	 * @return true if it is possible (edge exists in graph) to go from source
	 *         to target.
	 */
	@GuardedBy("sfg")
	public boolean canGoTo(StateVertex source, StateVertex target) {
		synchronized (sfg) {
			return sfg.containsEdge(source, target)
					|| sfg.containsEdge(target, source);
		}
	}

	/**
	 * Convenience method to find the Dijkstra shortest path between two states
	 * on the graph.
	 * 
	 * @param start
	 *            the start state.
	 * @param end
	 *            the end state.
	 * @return a list of shortest path of clickables from the state to the end
	 */
	public List<Eventable> getShortestPath(StateVertex start, StateVertex end) {
		return DijkstraShortestPath.findPathBetween(sfg, start, end);
	}

	/**
	 * Return all the states in the StateFlowGraph.
	 * 
	 * @return all the states on the graph.
	 */
	public Set<StateVertex> getAllStates() {
		return sfg.vertexSet();
	}

	/**
	 * Return all the edges in the StateFlowGraph.
	 * 
	 * @return a Set of all edges in the StateFlowGraph
	 */
	public Set<Eventable> getAllEdges() {
		return sfg.edgeSet();
	}

	/**
	 * Retrieve the copy of a state from the StateFlowGraph for a given
	 * StateVertix. Basically it performs v.equals(u).
	 * 
	 * @param state
	 *            the StateVertix to search
	 * @return the copy of the StateVertix in the StateFlowGraph where
	 *         v.equals(u)
	 */
	private StateVertex getStateInGraph(StateVertex state) {
		Set<StateVertex> states = getAllStates();

		for (StateVertex st : states) {
			if (state.equals(st)) {
				return st;
			}
		}

		return null;
	}

	/**
	 * @return Dom string average size (byte).
	 */
	public int getMeanStateStringSize() {
		final Mean mean = new Mean();

		for (StateVertex state : getAllStates()) {
			mean.increment(state.getDomSize());
		}

		return (int) mean.getResult();
	}

	/**
	 * @param state
	 *            The starting state.
	 * @return A list of the deepest states (states with no outgoing edges).
	 */
	public List<StateVertex> getDeepStates(StateVertex state) {
		final Set<String> visitedStates = new HashSet<String>();
		final List<StateVertex> deepStates = new ArrayList<StateVertex>();

		traverse(visitedStates, deepStates, state);

		return deepStates;
	}

	private void traverse(Set<String> visitedStates,
			List<StateVertex> deepStates, StateVertex state) {
		visitedStates.add(state.getName());

		Set<StateVertex> outgoingSet = getOutgoingStates(state);

		if ((outgoingSet == null) || outgoingSet.isEmpty()) {
			deepStates.add(state);
		} else {
			if (cyclic(visitedStates, outgoingSet)) {
				deepStates.add(state);
			} else {
				for (StateVertex st : outgoingSet) {
					if (!visitedStates.contains(st.getName())) {
						traverse(visitedStates, deepStates, st);
					}
				}
			}
		}
	}

	private boolean cyclic(Set<String> visitedStates,
			Set<StateVertex> outgoingSet) {
		int i = 0;

		for (StateVertex state : outgoingSet) {
			if (visitedStates.contains(state.getName())) {
				i++;
			}
		}

		return i == outgoingSet.size();
	}

	/**
	 * This method returns all possible paths from the index state using the
	 * Kshortest paths.
	 * 
	 * @param index
	 *            the initial state.
	 * @return a list of GraphPath lists.
	 */
	public List<List<GraphPath<StateVertex, Eventable>>> getAllPossiblePaths(
			StateVertex index) {
		final List<List<GraphPath<StateVertex, Eventable>>> results = new ArrayList<List<GraphPath<StateVertex, Eventable>>>();

		final KShortestPaths<StateVertex, Eventable> kPaths = new KShortestPaths<StateVertex, Eventable>(
				this.sfg, index, Integer.MAX_VALUE);

		for (StateVertex state : getDeepStates(index)) {

			try {
				List<GraphPath<StateVertex, Eventable>> paths = kPaths
						.getPaths(state);
				results.add(paths);
			} catch (Exception e) {
				// TODO Stefan; which Exception is catched here???Can this be
				// removed?
				LOG.error("Error with " + state.toString(), e);
			}

		}

		return results;
	}

	/**
	 * Return the name of the (new)State. By using the AtomicInteger the
	 * stateCounter is thread-safe
	 * 
	 * @return State name the name of the state
	 */
	public String getNewStateName() {
		stateCounter.getAndIncrement();
		String state = makeStateName(stateCounter.get(), false);
		return state;
	}

	/**
	 * Make a new state name given its id. Separated to get a central point when
	 * changing the names of states. The automatic state names start with
	 * "state" and guided ones with "guide".
	 * 
	 * @param id
	 *            the id where this name needs to be for.
	 * @return the String containing the new name.
	 */
	private String makeStateName(int id, boolean guided) {

		if (guided) {
			return "guided" + id;
		}

		return "state" + id;
	}

	public boolean isInitialState(StateVertex state) {
		return initialState.equals(state);
	}
}
