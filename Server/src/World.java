import java.awt.peer.SystemTrayPeer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
/***********************************************************************
	This thread will handle input processing, physics and frame updates 
************************************************************************/

public class World implements Runnable  {
	private List<List<Organ>> organsListsCopy = new ArrayList<>();
	private BlockingQueue<Uinput> inputs;	// This class acts as consumer, it takes off the queue
	private long lastTime;

	public World(BlockingQueue<Uinput> queue) {
		this.inputs = queue;
		initFPS();
	}
	
	private void copyOrgansList(){
		organsListsCopy.clear();
		synchronized (GameDs.oLck) {
			organsListsCopy.addAll(0, GameDs.organsLists);
		}
	}	

	/*
	// Collision between two organs
	private void orgorgCollision(int time){
		for (int i = 0; i < organsCopy.size()-1; i++) {		
			GameObj p1 = organsCopy.get(i);
			for (int j = i+2; j < organsCopy.size(); j++) {
			    GameObj p2 = organsCopy.get(j);
				if(GameObj.intersect(p1, p2)) {	 
					//   TODO: Handle collisions
				}
			}	
		}
	
	}
	*/
	
	// Collision between a blob and an organ
	private void orgblobCollision(){
		
	}
	
	// Send new data to msg buffer to be handled by the broadcasting thread
	private void dispatchToMsgBuf(){
		synchronized (GameDs.mLck) {
			int cur = -1;
			for (List<Organ> orgList : organsListsCopy) {
				for (Organ org : orgList)
					GameDs.msgBuf.add(
						//((org.owner!=cur) ? org.owner : "") + ","+org.getData()
							org.owner + ","+ org.getData()	);
			}
		}
	}

	// calculate CM for curPlayer's organs
	private void calCM(List<Organ> curPlayer){
		double avgX = 0.0, avgY = 0.0;
		int count = curPlayer.size();

		for(int i=0; i < count; i++) {
			avgX += curPlayer.get(i).pos.x;
			avgY += curPlayer.get(i).pos.y;
		}

		for(int i=0; i < count; i++)
			curPlayer.get(i).mpCM = new Vector(0,0, avgX/count, avgY/count);
	}

	private void constrain(List<Organ> curPlayer) {

		double directX = curPlayer.get(0).mpDirect.x,
			   directY = curPlayer.get(0).mpDirect.y;

		// after the organs are packed, they can't keep going in their direction, they have to start going in the CM direction
		for (int i = 0; i < curPlayer.size(); i++) {
			Organ org = curPlayer.get(i);
			if (org.lock && (org.applyPosEase == false) && (org.applyPosEase == false)) {
				double mag = Math.sqrt(org.vel.x * org.vel.x + org.vel.y * org.vel.y);
				double ang = Math.atan2(directY, directX);
				org.vel = new Vector(ang, mag);
			}
		}

		// check for collision between mp's organs
		for (int i = 0; i < curPlayer.size() - 1; i++) {
			Organ org1 = curPlayer.get(i);
			for (int j = i + 1; j < curPlayer.size(); j++) {
				Organ org2 = curPlayer.get(j);

				double radSum = org2.size + org1.size;        // sum of radii
				double distSqr = distSq(org1.pos.x, org1.pos.y, org2.pos.x, org2.pos.y);    // distance between centers squared

				if (radSum*radSum + 0.5 > distSqr) {        // if there's an intersection

					double interleave = radSum - Math.sqrt(distSqr);    // how much are the two circles intersecting?  r1 + r2 - distance

					// create a vector o12 going from org1 to org2
					Vector o12 = new Vector( org1.pos.x, org1.pos.y,
							                 org2.pos.x, org2.pos.y );
					o12.Normalize();
					// push the two organs apart: push org2 in the direction of the o12, and org1 in the opposite direction of o12
					// the exact distance each one will be pushed(from its original location) is interleave/2
					o12.Scale(interleave/2.0);
					org2.pos.add(o12);		// push in direction of o12
					o12.Scale(-1);
					org1.pos.add(o12);		// push in opp direction of o12

					// lock the 2 organs since they're touching and should no longer move relative to each other
					org1.lock = true;
					org2.lock = true;
				}

			}

		}

	}

	// Input processing: remember this method shares the thread-safe input buffer!!
	private void procInput() {
		if(inputs.isEmpty()) return;

		int batchSize = 30;		// how many inputs to process each call
		while( (batchSize > 0) && (!inputs.isEmpty()) ) {

			Uinput input = inputs.remove();
			List<Organ> curPlayer = null;

			// find to whom does this input belong, store it in curPlayer
			for (List<Organ> player : organsListsCopy) {
				if(player.get(0).owner == input.pid)
					curPlayer = player;
				break;
			}

			// FIX: curPlayer is null sometimes !!

			// assert curPlayer ain't null
			if(curPlayer == null )
				System.out.println("---Fatal Error: curPlayer is null");
			if(curPlayer.isEmpty())
				System.out.println("---Fatal Error: curPlayer is empty");

			// handle input for curPlayer
			List<Organ> tempOrgans = new ArrayList<>();
			for (int i = 0; i < curPlayer.size(); i++) {
				// each organ will try to move towards the mouse pointer, but later when the organs are packed together, they'll follow CM direction

				double xspd = curPlayer.get(i).vel.x,
				       yspd = curPlayer.get(i).vel.y;
				double mag =  Math.sqrt(xspd * xspd + yspd * yspd);
				double ang = Math.atan2(input.ydir  - curPlayer.get(i).pos.y + curPlayer.get(i).mpCM.y,            // the direction angle from the organ to the mouse location
						input.xdir  - curPlayer.get(i).pos.x + curPlayer.get(i).mpCM.x);

				curPlayer.get(i).vel = new Vector(ang, mag);
				curPlayer.get(i).mpDirect = new Vector(0,0, input.xdir, input.ydir);

				if (input.type == 1)	// if this input was md
					tempOrgans.add(curPlayer.get(i).split());
			}

			// add any newly created organs to this player's organs list
			for (int i = 0; i < tempOrgans.size(); i++)
				curPlayer.add(tempOrgans.get(i));

		}

	}

	@Override
	public void run() {
		
	/* Delta-timing variables */
		lastTime = System.nanoTime();
		final int times = 60;				// the physics (coords updates) should update 60 times a second
		double ns = 1000000000.0 / times;  	// the time till the next physics update in nano seconds  
		double delta = 0;				
		
		while(true) {
			copyOrgansList();		// oLck blocking
			procInput();			// input buffer blocking

		    /* Delta-timing: guarantees consistent movements and updates regardless of the time each frame takes */
		    long now = System.nanoTime();
			displayFPS(now);
			delta += (now - lastTime) / ns;  // detla += actual elapsed time / time required for 1 update
		    lastTime = now;
		    if(delta >= 1) { // if enough time has passed: update

				if(!organsListsCopy.isEmpty()) {
				//	System.out.println("pos: "+organsListsCopy.get(0).get(0).pos.x + ",  " + organsListsCopy.get(0).get(0).pos.y);
					System.out.println("vel: "+organsListsCopy.get(0).get(0).vel.x + ",  " + organsListsCopy.get(0).get(0).vel.y);
				}
		    	for (List<Organ> li : organsListsCopy) {
					for (Organ temp : li)
						temp.update(1.0 / times);
					constrain(li);
					calCM(li);
				}

				delta--;					
		    }

		    /* -------------------------------------------------------------------------------------------------- */
			
		   // orgorgCollision(times);
		   // orgblobCollision();
			dispatchToMsgBuf();		// mLck blocking

			/*
				TODO:
				fix time stepping/FPS
				currently server and client are out of sync

			*/

			try { Thread.sleep(1000/times); }
			catch (InterruptedException e) { e.printStackTrace(); }
			
		}	// end while		
		
	} // end run()


/********************************* Helpers *********************************/

	double distSq(double x1, double y1, double x2, double y2) {
		return (x2-x1)*(x2-x1) + (y2-y1)*(y2-y1);
	}

	double fps[];
	int _ind_ = 0;
	void initFPS() {
		this.fps = new double[10];
		for(int i=0; i < 10; i++) fps[0] = 0.0;
	}
	double calAVG() {
		double count = 0.0;
		for (int i=fps.length-1; i>0; i--)
			count+=fps[i];

		return Math.round(count/fps.length);
	}
	void displayFPS(long now){
		fps[_ind_%10] = 1000000000.0/(now-lastTime);
		_ind_++;
		System.out.println("FPS: "+calAVG());
	}

}