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

import org.apache.commons.lang3.SerializationUtils;
import org.apache.commons.math.stat.descriptive.moment.Mean;

import org.jgrapht.DirectedGraph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.DijkstraShortestPath;
import org.jgrapht.alg.KShortestPaths;

import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.index.Index;
import org.neo4j.graphdb.index.IndexHits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.neo4j.helpers.UTF8;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

/**
 * The State-Flow Graph is a directed multigraph with states (StateVetex) on the
 * vertices and clickables (Eventables) on the edges. It stores the data in a
 * graph database. The graph database of choice for this version of StateFlowGraph
 * class is neo4j community edition.
 */

public class StateFlowGraph implements Serializable {

	/**
	 * serial version for persisting the class
	 */
	private static final long serialVersionUID = 8765685878231494104L;

	private static final Logger LOG = LoggerFactory
			.getLogger(StateFlowGraph.class.getName());

	/**
	 * Intermediate counter for the number of states, not relaying on
	 * getAllStates.size() because of Thread-safety.
	 */
	private final AtomicInteger stateCounter = new AtomicInteger();

	private final StateVertex initialState;

	// The directory path for saving the graph database created by neo4j for
	// storing the state flow graph

	private static final String DB_PATH = "target/state-flow-graph-db/Fresh_";

	// the connector or access point to the graph database

	private final GraphDatabaseService sfgDb;

	// keys used for key-value pairs. The key-value pairs are the main place holder
	// for used in neo4j data model of a graph. The data is stored in edges and nodes
	// of the graph as "properties"

	// the key for storing the persisted StateVertex objects
	private static final String STATE_VERTEX_KEY = "stateVertex";

	// the key for storing the DOM objects
	private static final String STRIPPED_DOM_KEY = "strippedDOM";

	// the key for storing the persisted source StateVertex objects
	private static final String SOURCE_KEY = "source";

	// the key for storing the persisted source target StateVertex objects
	private static final String TARGET_KEY = "target";

	// the key for storing the persisted Eventable objects
	private static final String CLICKABLE_KEY = "clickable";

	// the combined key for storing the persisted triples of
	// (source StateVertex,Eventable,target StateVertex) saved in a string array
	// of length 3
	// this is used for indexing edges
	private static final String EDGE_COMBNINED_KEY = "edgeCombined";
	

	// the id used for the for node indexer object
	private static final String NODES_INDEX_NAME = "nodes";

	// the id used for the for edge indexer object
	private static final String EDGES_INDEX_NAME = "edges";


	// indexing data structures for fast retrieval
	private static Index<Node> nodeIndex;
	private static Index<Relationship> edgesIndex;

	// The edges in the graph are modeled as relationships between nodes.
	// These relationships have enum names and they also are able to have
	// properties for holding the data associated with the edges in the application

	// the relationship between a source vertex and the destination vertex

	private static enum RelTypes implements RelationshipType {
		
		// there is a directed edge from state A to state B
		// if there is a clickable in state A which transitions from A to B.
		
		TRANSITIONS_TO 
	}

	/**
	 * The constructor.
	 * 
	 * @param initialState
	 *            the state to start from.
	 */
	public StateFlowGraph(StateVertex initialState) {

		Preconditions.checkNotNull(initialState);

		// creating the graph database

		// get the database to rewrite on previous database file 
		// each time it is initiated
		
		// fresh is used to ensure that every time we run the program a
		// fresh empty database is used for storing the data
		
		long fresh = System.nanoTime();
		String path = DB_PATH + fresh;
		sfgDb = new GraphDatabaseFactory().newEmbeddedDatabase(path);

		// for quick indexing and retrieval of nodes. This data structure is a
		// additional
		// capability beside
		// the main graph data structures which is comprised of nodes and edges

		nodeIndex = sfgDb.index().forNodes(NODES_INDEX_NAME);

		// again similar to nodeIndex this is a cross indexing of the edges for
		// fast retrieval

		edgesIndex = sfgDb.index().forRelationships(EDGES_INDEX_NAME);

		// adding the first node to the graph
		this.addState(initialState);

		this.initialState = initialState;

		// adding a shutdown hook to ensure the database will be shut down even if
		// the program breaks

		//registerShutdownHook(sfgDb);

	}

	/**
	 * Registering a shutdown hook for the database instance so as to shut it
	 * down nicely when the VM exits
	 * 
	 * @param graphDatabaseService
	 *            the database for which a shutdown hook will be registered
	 * 
	 */
	private static void registerShutdownHook(
			final GraphDatabaseService graphDatabaseService) {
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				graphDatabaseService.shutdown();
			}
		});
	}

	/**
	 * 
	 * @param stateVertex
	 *            the state which will be serialized
	 * @return a byte array containing persisted byte array of the stateVertex object.
	 */

	public static byte[] serializeStateVertex(StateVertex stateVertex) {

		// result holder
		byte[] serializedStateVertex = null;

		// this an output stream that does not require writing to the file and
		// instead
		// the output stream is stored in a buffer
		// we use this class to utilize the Java serialization api which writes
		// and reads
		// objects to and from streams

		ByteArrayOutputStream baos = new ByteArrayOutputStream();

		try {

			ObjectOutputStream oos = new ObjectOutputStream(baos);

			// Serializing the stateVertex object to the stream

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

			// Closing streams

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

<<<<<<< HEAD
	public static byte[] serializeEventable(Eventable eventable, int i) {
		byte[] result = SerializationUtils.serialize(eventable);
		return result;
	}
=======
	/**
	 * Intermediate counter for the number of states, not relaying on getAllStates.size() because of
	 * Thread-safety.
	 */
	private final AtomicInteger stateCounter = new AtomicInteger();
	private final AtomicInteger nextStateNameCounter = new AtomicInteger();
>>>>>>> master

	public static Eventable deserializeEventable(byte[] serializedEventable,
			int i) {
		Eventable results = (Eventable) SerializationUtils
				.deserialize(serializedEventable);

		return results;

<<<<<<< HEAD
	}

	public static byte[] serializeEventable(Eventable eventable) {

		byte[] serializedEventable = null;

		// this an output stream that does not require writing to the file and
		// instead
		// the output stream is stored in a buffer
		// we use this class to utilize the Java serialization api which writes
		// and reads
		// object to and from streams

		ByteArrayOutputStream baos = new ByteArrayOutputStream();

		try {

			ObjectOutputStream oos = new ObjectOutputStream(baos);

			// serializing the Eventable object to the stream

			oos.writeObject(eventable);

			// converting the byte array to UTF-8 string for portability reasons

			serializedEventable = baos.toByteArray();

			// closing streams

			oos.close();
			baos.close();

		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return serializedEventable;
	}

	public static Eventable deserializeEventable(byte[] serializedEventable) {
		// the returned value

		Eventable deserializedEventable = null;

		try {

			ByteArrayInputStream bais = new ByteArrayInputStream(
					serializedEventable);

			ObjectInputStream ois = new ObjectInputStream(bais);

			deserializedEventable = (Eventable) ois.readObject();

			// Closing streams

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

		return deserializedEventable;

	}

	public boolean addEdge(StateVertex sourceVert, StateVertex targetVert,
			Eventable eventable) {

		boolean exists = false;
		byte[] serializedEventable = serializeEventable(eventable,1);

		// for (Relationship relationship: edgesIndex.get(SOURCE_KEY,
		// sourceVert.getStrippedDom().getBytes())){
		// if
		// (relationship.getProperty(TARGET_KEY).equals(targetVert.getStrippedDom().getBytes())){
		// if(relationship.getProperty(CLICKABLE_KEY).equals(serializedEventable)){
		// exists= true;
		// return false;
		// }
		// }
		// }
		//
		// for (Relationship relationship:
		// edgesIndex.get("clickable",serializedEventable )){
		// if
		// (relationship.getStartNode().getProperty(DOM_KEY).equals(serializeStateVertex(sourceVert))){
		// if
		// (relationship.getEndNode().getProperty(DOM_KEY).equals(serializeStateVertex(targetVert))){
		//
		// alreadyExits = true;
		// return false;
		// }
		// }
		// }

		//
		Relationship toBeAddedEdge = null;
		Relationship alreadyExists = null;

		String[] combinedEdgeKey = new String[3];
		combinedEdgeKey[0] = sourceVert.getStrippedDom();
		combinedEdgeKey[2] = targetVert.getStrippedDom();
		combinedEdgeKey[1] = UTF8.decode(serializedEventable);

		Transaction tx = sfgDb.beginTx();
		try {

			Node sourceNode = getNodeFromDB(sourceVert.getStrippedDom()); // nodeIndex.get(STRIPPED_DOM_KEY,
																			// sourceVert.getStrippedDom().getBytes()).getSingle();
			Node targetNode = getNodeFromDB(targetVert.getStrippedDom());// nodeIndex.get(STRIPPED_DOM_KEY,
																			// targetVert.getStrippedDom().getBytes()).getSingle();
			toBeAddedEdge = sourceNode.createRelationshipTo(targetNode,
					RelTypes.TRANSITIONS_TO);

			// adding the new edge to the index. it returns null if the edge is
			// successfully added
			// and returns the found edge if and identical edge already exists
			// in the index.
			// alreadyExists = edgesIndex.putIfAbsent(toBeAddedEdge,
			// EDGE_COMBNINED_KEY, combinedEdgeKey);
			alreadyExists = edgePutIfAbsent(toBeAddedEdge, EDGE_COMBNINED_KEY,
					combinedEdgeKey);

			if (alreadyExists != null) {
				exists = true;
				tx.failure();
			} else {
				exists = false;

				toBeAddedEdge.setProperty(CLICKABLE_KEY, serializedEventable);
				toBeAddedEdge.setProperty(SOURCE_KEY,
						UTF8.encode(sourceVert.getStrippedDom()));
				toBeAddedEdge.setProperty(TARGET_KEY,
						UTF8.encode(targetVert.getStrippedDom()));
			}
			tx.success();
		} finally {
			tx.finish();

		}

		if (exists) {

			return false;
		} else {
			return true;

		}

	}

	private Relationship edgePutIfAbsent(Relationship toBeAddedEdge,
			String key, String[] combinedEdgeKey) {

		for (Relationship edge : edgesIndex.query(key, "*")) {

			byte[] serSourceDom = (byte[]) edge.getProperty(SOURCE_KEY);
			byte[] serTargetDom = (byte[]) edge.getProperty(TARGET_KEY);
			byte[] serClickable = (byte[]) edge.getProperty(CLICKABLE_KEY);

			String sourceDom = UTF8.decode(serSourceDom);
			String targetDom = UTF8.decode(serTargetDom);

			if (sourceDom.equals(combinedEdgeKey[0])
					&& targetDom.equals(combinedEdgeKey[2])
					&& UTF8.decode(serClickable).equals(combinedEdgeKey[1])) {
				return edge;

			}
		}

		edgesIndex.add(toBeAddedEdge, key, combinedEdgeKey);

		return null;
	}

	public StateVertex addState(StateVertex stateVertix) {
		return addState(stateVertix, true);
	}

	public StateVertex addState(StateVertex state, boolean correctName) {

		// the node to be added and updated for storing the state in the
		// database

		Node toBeAddedNode;

		// for saving the returned result of the method putIfAbsent

		Node alreadyEsixts;

		// starting the transaction
		Transaction tx = sfgDb.beginTx();
		try {
			// adding the place holder for the state which is giong to be added
			// to the graph database

			toBeAddedNode = sfgDb.createNode();

			// indexing the state in Index manager. the key that we are using
			// for indexing is the stripped_dom field, this in perticular is
			// complient with the
			// domChanged method in the class Crawler
			//
			// the new node is added to the index and

			// alreadyEsixts = nodeIndex.putIfAbsent(toBeAddedNode,
			// STRIPPED_DOM_KEY, UTF8.encode(state
			// .getStrippedDom()));
			alreadyEsixts = putIfAbsent(toBeAddedNode,
					UTF8.encode(state.getStrippedDom()));

			if (alreadyEsixts != null) {
				// the state is already indexed
				LOG.debug("Graph already contained vertex {}", state);

				// because the state already exists in the graph the transaction
				// is marked for
				// being rolled back
				tx.failure();
			}

			// correcting the name
			int count = stateCounter.incrementAndGet();
			LOG.debug("Number of states is now {}", count);
			if (correctName) {
				correctStateName(state);
=======
		// creating the graph db
		
//		sfgDb = new GraphDatabaseFactory().newEmbeddedDatabase(DB_PATH);
//		
//		// adding a shutdown hook to ensure the db will be shut down even if 
//		// the program breaks
//		
//		registerShutdownHook(sfgDb);
//		

		sfg = new DirectedMultigraph<>(Eventable.class);
//		
//		// add the first node to the graph
		
		sfg.addVertex(initialState);
		stateCounter.incrementAndGet();
		this.initialState = initialState;
		LOG.debug("Initialized the stateflowgraph with an initial state");
	}

	/**
	 * Adds a state (as a vertix) to the State-Flow Graph if not already present. More formally,
	 * adds the specified vertex, v, to this graph if this graph contains no vertex u such that
	 * u.equals(v). If this graph already contains such vertex, the call leaves this graph unchanged
	 * and returns false. In combination with the restriction on constructors, this ensures that
	 * graphs never contain duplicate vertices. Throws java.lang.NullPointerException - if the
	 * specified vertex is null. This method automatically updates the state name to reflect the
	 * internal state counter.
	 * 
	 * @param stateVertix
	 *            the state to be added.
	 * @return the clone if one is detected null otherwise.
	 * @see org.jgrapht.Graph#addVertex(Object)
	 */
	public StateVertex putIfAbsent(StateVertex stateVertix) {
		return putIfAbsent(stateVertix, true);
	}

	/**
	 * Adds a state (as a vertix) to the State-Flow Graph if not already present. More formally,
	 * adds the specified vertex, v, to this graph if this graph contains no vertex u such that
	 * u.equals(v). If this graph already contains such vertex, the call leaves this graph unchanged
	 * and returns false. In combination with the restriction on constructors, this ensures that
	 * graphs never contain duplicate vertices. Throws java.lang.NullPointerException - if the
	 * specified vertex is null.
	 * 
	 * @param stateVertix
	 *            the state to be added.
	 * @param correctName
	 *            if true the name of the state will be corrected according to the internal state
	 *            counter.
	 * @return the clone if one is detected null otherwise.
	 * @see org.jgrapht.Graph#addVertex(Object)
	 */
	@GuardedBy("sfg")
	public StateVertex putIfAbsent(StateVertex stateVertix, boolean correctName) {
		synchronized (sfg) {
			boolean added = sfg.addVertex(stateVertix);
			if (added) {
				int count = stateCounter.incrementAndGet();
				LOG.debug("Number of states is now {}", count);
				if (correctName) {
					correctStateName(stateVertix);
				}
				return null;
			} else {
				// Graph already contained the vertix
				LOG.debug("Graph already contained vertex {}", stateVertix);
				return this.getStateInGraph(stateVertix);
>>>>>>> master
			}

			// serializing the state
			byte[] serializedSV = serializeStateVertex(state);

			// adding the state property which is the main data we store for
			// each node (i.e. each StateVertex)
			toBeAddedNode.setProperty(STATE_VERTEX_KEY, serializedSV);
			toBeAddedNode.setProperty(STRIPPED_DOM_KEY,
					UTF8.encode(state.getStrippedDom()));

			// flagging successful transaction
			tx.success();
		} finally {
			tx.finish();
		}

		if (alreadyEsixts == null) {
			// the state was absent so it was stored in the database
			return null;
		} else {

//			// Return the state retrieved from database in case the state is
//			// already present in the graph
//			return (StateVertex) deserializeStateVertex((byte[]) alreadyEsixts
//					.getProperty(STATE_VERTEX_KEY));
			return state;
		}

	}

	private Node getNodeFromDB(String strippedDom) {
		for (Node node : nodeIndex.query(STRIPPED_DOM_KEY, "*")) {

			byte[] serializedNode = (byte[]) node.getProperty(STATE_VERTEX_KEY);

			StateVertex state = deserializeStateVertex(serializedNode);

			String newDom = strippedDom;
			String prev = state.getStrippedDom();

			if (newDom.equals(prev)) {
				return node;

			}

		}

		return null;

	}

	private Node putIfAbsent(Node toBeAddedNode, byte[] StrippedDom) {

		for (Node node : nodeIndex.query(STRIPPED_DOM_KEY, "*")) {

			byte[] serializedNode = (byte[]) node.getProperty(STATE_VERTEX_KEY);

			StateVertex state = deserializeStateVertex(serializedNode);

			String newDom = UTF8.decode(StrippedDom);
			String prev = state.getStrippedDom();

			if (newDom.equals(prev)) {
				return node;

			}

		}
		nodeIndex.add(toBeAddedNode, STRIPPED_DOM_KEY, StrippedDom);

		return null;
	}

	private void correctStateName(StateVertex stateVertix) {
		// we might need to luck the database here
		// the -1 is for the "index" state.
		IndexHits<Node> ih = nodeIndex.query(STRIPPED_DOM_KEY, "*");
		int totalNumberOfStates = ih.size() - 1;
		ih.close();
		String correctedName = makeStateName(totalNumberOfStates,
				stateVertix.isGuidedCrawling());
		if (!"index".equals(stateVertix.getName())
				&& !stateVertix.getName().equals(correctedName)) {
			LOG.info("Correcting state name from {}  to {}",
					stateVertix.getName(), correctedName);
			stateVertix.setName(correctedName);
		}
	}

	// it sounds like the name is corrected based on the number of states read
	// from the graph
	// but it's safer to use the atomic integer though we might end up having
	// sparse name space, i.e. we end up having gaps between the numbers
	// associated to startes.

	/**
	 * @return the string representation of the graph.
	 * @see org.jgrapht.DirectedGraph#toString()
	 */
	@Override
	public String toString() {
		return "stateFlowGraph";
	}

	/**
	 * Returns a set of all clickables outgoing from the specified vertex.
	 * 
	 * @param stateVertix
	 *            the state vertix.
	 * @return a set of the outgoing edges (clickables) of the stateVertix.
	 * @see org.jgrapht.DirectedGraph#outgoingEdgesOf(Object)
	 */
<<<<<<< HEAD
	public Set<Eventable> getOutgoingClickables(StateVertex stateVertix) {
		Set<Eventable> outgoing = new HashSet<Eventable>();
		Node state = getNodeFromDB(stateVertix.getStrippedDom());
		for (Relationship edge : state.getRelationships(
				RelTypes.TRANSITIONS_TO, Direction.OUTGOING)) {
			byte[] serializedEvantable = (byte[]) edge
					.getProperty(CLICKABLE_KEY);
			Eventable eventable =  deserializeEventable(
					serializedEvantable, 1);
			outgoing.add(eventable);
		}

		return outgoing;
=======
	public ImmutableSet<Eventable> getOutgoingClickables(StateVertex stateVertix) {
		return ImmutableSet.copyOf(sfg.outgoingEdgesOf(stateVertix));
>>>>>>> master
	}

	/**
	 * Returns a set of all edges incoming into the specified vertex.
	 * 
	 * @param stateVertix
	 *            the state vertix.
	 * @return a set of the incoming edges (clickables) of the stateVertix.
	 * @see org.jgrapht.DirectedGraph#incomingEdgesOf(Object)
	 */
<<<<<<< HEAD
	public Set<Eventable> getIncomingClickable(StateVertex stateVertix) {

		Set<Eventable> incoming = new HashSet<Eventable>();
		Node state = getNodeFromDB(stateVertix.getStrippedDom());

		for (Relationship edge : state.getRelationships(
				RelTypes.TRANSITIONS_TO, Direction.INCOMING)) {
			byte[] serializedEvantable = (byte[]) edge
					.getProperty(CLICKABLE_KEY);
			Eventable eventable =  deserializeEventable(
					serializedEvantable, 1);
			incoming.add(eventable);
		}

		return incoming;

=======
	public ImmutableSet<Eventable> getIncomingClickable(StateVertex stateVertix) {
		return ImmutableSet.copyOf(sfg.incomingEdgesOf(stateVertix));
>>>>>>> master
	}

	/**
	 * Returns the set of outgoing states.
	 * 
	 * @param stateVertix
	 *            the state.
	 * @return the set of outgoing states from the stateVertix.
	 */
<<<<<<< HEAD
	public Set<StateVertex> getOutgoingStates(StateVertex stateVertix) {
=======
	public ImmutableSet<StateVertex> getOutgoingStates(StateVertex stateVertix) {
		final Set<StateVertex> result = new HashSet<>();
>>>>>>> master

		final Set<StateVertex> outgoing = new HashSet<StateVertex>();

		Node sourceNode = getNodeFromDB(stateVertix.getStrippedDom());

		for (Relationship edge : sourceNode.getRelationships(
				RelTypes.TRANSITIONS_TO, Direction.OUTGOING)) {
			Node endNode = edge.getEndNode();
			byte[] serializedState = (byte[]) endNode
					.getProperty(STATE_VERTEX_KEY);
			StateVertex targetState = (StateVertex) deserializeStateVertex(serializedState);
			outgoing.add(targetState);
		}

<<<<<<< HEAD
		return outgoing;

=======
		return ImmutableSet.copyOf(result);
>>>>>>> master
	}

	/**
	 * @param clickable
	 *            the edge.
	 * @return the target state of this edge.
	 */
	public StateVertex getTargetState(Eventable clickable) {

		byte[] serializedEventable = serializeEventable(clickable,1);

		Relationship edge = (Relationship) edgesIndex.get(CLICKABLE_KEY,
				serializedEventable).getSingle();

		Node targetNode = edge.getEndNode();

		byte[] srializedState = (byte[]) targetNode
				.getProperty(STATE_VERTEX_KEY);
		StateVertex target = deserializeStateVertex(srializedState);

		return target;
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
	public boolean canGoTo(StateVertex source, StateVertex target) {

		Node sourceNode = getNodeFromDB(source.getStrippedDom());
		for (Relationship edge : sourceNode.getRelationships(
				RelTypes.TRANSITIONS_TO, Direction.OUTGOING)) {

			Node targetNode = edge.getEndNode();
			byte[] serializedNode = (byte[]) targetNode
					.getProperty(STATE_VERTEX_KEY);

			StateVertex ts = deserializeStateVertex(serializedNode);
			if (ts.equals(target)) {
				return true;
			}
		}
		
		// searching for back links
		Node tagetNode = getNodeFromDB(target.getStrippedDom());
		for (Relationship edge : tagetNode.getRelationships(
				RelTypes.TRANSITIONS_TO, Direction.OUTGOING)) {

			Node srcNode = edge.getEndNode();
			byte[] serializedNode = (byte[]) srcNode
					.getProperty(STATE_VERTEX_KEY);

			StateVertex ts = deserializeStateVertex(serializedNode);
			if (ts.equals(source)) {
				return true;
			}
		}

		
		

		return false;

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
//	public List<Eventable> getShortestPath(StateVertex start, StateVertex end) {
//		//return DijkstraShortestPath.findPathBetween(sfg, start, end);
//	}

	/**
	 * Return all the states in the StateFlowGraph.
	 * 
	 * @return all the states on the graph.
	 */
<<<<<<< HEAD
	public Set<StateVertex> getAllStates() {

		final Set<StateVertex> allStates = new HashSet<StateVertex>();

		for (Node node : nodeIndex.query(STRIPPED_DOM_KEY, "*")) {

			byte[] serializedNode = (byte[]) node.getProperty(STATE_VERTEX_KEY);

			StateVertex state = deserializeStateVertex(serializedNode);

			allStates.add(state);

		}

		return allStates;
=======
	public ImmutableSet<StateVertex> getAllStates() {
		return ImmutableSet.copyOf(sfg.vertexSet());
>>>>>>> master
	}

	/**
	 * Return all the edges in the StateFlowGraph.
	 * 
	 * @return a Set of all edges in the StateFlowGraph
	 */
<<<<<<< HEAD
	public Set<Eventable> getAllEdges() {

		final Set<Eventable> all = new HashSet<Eventable>();

		for (Relationship edge : edgesIndex.query(EDGE_COMBNINED_KEY, "*")) {

			byte[] serializededge = (byte[]) edge.getProperty(CLICKABLE_KEY);

			Eventable eventable = deserializeEventable(serializededge, 1);

			all.add(eventable);

		}

		return all;
=======
	public ImmutableSet<Eventable> getAllEdges() {
		return ImmutableSet.copyOf(sfg.edgeSet());
>>>>>>> master
	}

	/**
	 * Retrieve the copy of a state from the StateFlowGraph for a given
	 * StateVertix. Basically it performs v.equals(u).
	 * 
	 * @param state
	 *            the StateVertix to search
<<<<<<< HEAD
	 * @return the copy of the StateVertix in the StateFlowGraph where
	 *         v.equals(u)
=======
	 * @return the copy of the StateVertix in the StateFlowGraph where v.equals(u) or
	 *         <code>null</code> if not found.
>>>>>>> master
	 */
	private StateVertex getStateInGraph(StateVertex state) {
		for (StateVertex st : sfg.vertexSet()) {
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

		for (StateVertex state : sfg.vertexSet()) {
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
<<<<<<< HEAD
//	public List<List<GraphPath<StateVertex, Eventable>>> getAllPossiblePaths(
//			StateVertex index) {
//		final List<List<GraphPath<StateVertex, Eventable>>> results = new ArrayList<List<GraphPath<StateVertex, Eventable>>>();
//
//		final KShortestPaths<StateVertex, Eventable> kPaths = new KShortestPaths<StateVertex, Eventable>(
//				this.sfg, index, Integer.MAX_VALUE);
//
//		for (StateVertex state : getDeepStates(index)) {
//
//			try {
//				List<GraphPath<StateVertex, Eventable>> paths = kPaths
//						.getPaths(state);
//				results.add(paths);
//			} catch (Exception e) {
//				// TODO Stefan; which Exception is catched here???Can this be
//				// removed?
//				LOG.error("Error with " + state.toString(), e);
//			}
//
//		}
//
//		return results;
//	}
=======
	public List<List<GraphPath<StateVertex, Eventable>>> getAllPossiblePaths(StateVertex index) {
		final List<List<GraphPath<StateVertex, Eventable>>> results = Lists.newArrayList();

		final KShortestPaths<StateVertex, Eventable> kPaths =
		        new KShortestPaths<>(this.sfg, index, Integer.MAX_VALUE);

		for (StateVertex state : getDeepStates(index)) {

			try {
				List<GraphPath<StateVertex, Eventable>> paths = kPaths.getPaths(state);
				results.add(paths);
			} catch (Exception e) {
				// TODO Stefan; which Exception is catched here???Can this be removed?
				LOG.error("Error with " + state.toString(), e);
			}

		}

		return results;
	}
>>>>>>> master

	/**
	 * Return the name of the (new)State. By using the AtomicInteger the
	 * stateCounter is thread-safe
	 * 
	 * @return State name the name of the state
	 */
	public String getNewStateName() {
		String state = makeStateName(nextStateNameCounter.incrementAndGet(), false);
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

	/**
	 * @return The number of states, currently in the graph.
	 */
	public int getNumberOfStates() {
		return stateCounter.get();
	}
}
